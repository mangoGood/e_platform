package com.ecommerce.core.network

import com.ecommerce.core.datastore.TokenManager
import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.RefreshTokenRequest
import com.ecommerce.core.model.TokenPair
import dagger.Lazy
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 刷新令牌专用接口。
 *
 * 刻意与 [UserApi] 分开，并且由 **裸 OkHttpClient**（不挂 [TokenAuthenticator]、
 * 不挂 [AuthInterceptor]）构建的 Retrofit 创建。
 *
 * 如果刷新请求走了带 Authenticator 的那个 client：
 * 刷新失败返回 401 -> 触发 Authenticator -> 再次发起刷新 -> 又 401 …… 无限递归。
 *
 * 返回 [Call] 而不是 `suspend`：[TokenRefresher.refresh] 运行在 OkHttp 的
 * Authenticator 线程（非协程上下文），同步 `execute()` 才是自然写法，
 * 用 suspend 反而要套 `runBlocking`。
 */
interface RefreshApi {

    @POST("user/refresh")
    fun refresh(@Body request: RefreshTokenRequest): Call<ApiResponse<TokenPair>>
}

/**
 * 刷新结果。
 */
sealed class RefreshResult {

    /** 刷新成功（或发现别的线程已刷新完成），携带可用的新 access token */
    data class Success(val accessToken: String) : RefreshResult()

    /** 刷新失败且不可恢复（refreshToken 也过期/被作废），已清空登录态并广播强制登出 */
    data class Failure(val reason: String) : RefreshResult()

    /** 临时性失败（网络抖动），登录态保留，调用方本次放弃即可，下次还能再试 */
    data class Retryable(val reason: String) : RefreshResult()
}

/**
 * Access Token 刷新器，实现 **single-flight（单飞）** 语义。
 *
 * ## 单飞是怎么保证的
 * 1. [refresh] 整个方法加 `@Synchronized`（锁的是 TokenRefresher 单例本身）。
 *    N 个并发 401 到达时，只有 1 个线程能进入方法体，其余 N-1 个在监视器上排队等待。
 * 2. 拿到锁的第一个线程执行真正的 `POST user/refresh`，成功后把新 token 写进
 *    [TokenManager]，然后释放锁。
 * 3. 后续线程依次进入，第一件事就是比对
 *    `当前存储的 accessToken != 自己那次请求用的 staleAccessToken`。
 *    因为第 1 个线程已经把 token 换掉了，这个条件成立，于是**直接返回新 token，
 *    不再发第二次刷新请求**。
 *
 * 结果：无论多少个请求同时 401，`POST user/refresh` 只会被调用一次。
 *
 * 因为本类是 `@Singleton`，全 App 只有一把锁，跨 ViewModel / 跨 Repository 同样生效。
 */
@Singleton
class TokenRefresher @Inject constructor(
    private val tokenManager: TokenManager,
    private val sessionManager: SessionManager,
    /**
     * 用 [Lazy] 注入而不是直接注入 [RefreshApi]：
     * 打破 `OkHttpClient -> TokenAuthenticator -> TokenRefresher -> RefreshApi -> Retrofit -> OkHttpClient`
     * 这条依赖链在 Hilt 图上的成环风险（`Found a dependency cycle`）。
     * Lazy 让 RefreshApi 推迟到第一次真正刷新时才实例化。
     */
    private val refreshApi: Lazy<RefreshApi>
) {

    /**
     * 用 refreshToken 换一个新的 access token。**线程安全且单飞。**
     *
     * @param staleAccessToken 触发 401 的那个（已失效的）access token
     * @return 刷新结果
     */
    @Synchronized
    fun refresh(staleAccessToken: String): RefreshResult {
        // ---- 单飞快速通道：别的线程已经刷新完了，直接复用它的成果 ----
        val currentToken = tokenManager.accessToken
        if (!currentToken.isNullOrEmpty() && currentToken != staleAccessToken) {
            return RefreshResult.Success(currentToken)
        }

        val currentRefreshToken = tokenManager.refreshToken
        if (currentRefreshToken.isNullOrEmpty()) {
            return failHard("登录已过期，请重新登录")
        }

        // 作废已被服务端拒绝的 access token。
        // 必须用 clearAccessToken() 而不是 clearAll()——后者会把 refreshToken 一起删掉，
        // 下面那行就永远拿不到凭据了。
        tokenManager.clearAccessToken()

        val response = try {
            refreshApi.get().refresh(RefreshTokenRequest(currentRefreshToken)).execute()
        } catch (e: Exception) {
            // 网络抖动：保留 refreshToken，不强制登出，下次请求还能再试一次
            return RefreshResult.Retryable(ErrorMapper.toApiException(e).message)
        }

        if (!response.isSuccessful) {
            val error = ErrorMapper.fromErrorResponse(response)
            // 401/403 说明 refreshToken 本身也废了 -> 只能重新登录
            return if (error.httpCode == 401 || error.httpCode == 403) {
                failHard(error.message)
            } else {
                RefreshResult.Retryable(error.message)
            }
        }

        val body = response.body()
        if (body == null || (body.code != null && body.code != 200)) {
            val message = body?.message?.takeIf { it.isNotBlank() }
                ?: ErrorMapper.defaultMessage(response.code())
            return failHard(message)
        }

        val pair: TokenPair = body.data ?: return failHard("登录已过期，请重新登录")
        val newAccessToken = pair.token
        if (newAccessToken.isNullOrEmpty()) {
            return failHard("登录已过期，请重新登录")
        }

        // refreshToken 是轮换式的：后端每次都下发新的并立刻作废旧的，必须一并存下。
        tokenManager.saveTokens(
            accessToken = newAccessToken,
            refreshToken = pair.refreshToken,
            expiresIn = pair.expiresIn
        )
        return RefreshResult.Success(newAccessToken)
    }

    /**
     * 不可恢复的刷新失败：清空全部登录态并广播强制登出。
     *
     * @param reason 展示给用户的中文原因
     * @return [RefreshResult.Failure]
     */
    private fun failHard(reason: String): RefreshResult.Failure {
        tokenManager.clearAll()
        sessionManager.notifyForcedLogout(reason)
        return RefreshResult.Failure(reason)
    }
}
