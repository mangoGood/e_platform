package com.ecommerce.buyer.feature.auth

import com.ecommerce.core.datastore.TokenManager
import com.ecommerce.core.model.LoginRequest
import com.ecommerce.core.model.LoginResponse
import com.ecommerce.core.network.ApiException
import com.ecommerce.core.network.UserApi
import com.ecommerce.core.network.apiCall
import com.ecommerce.core.network.requireData
import com.ecommerce.core.network.requireSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 买家端登录态仓库。
 *
 * ## 改造要点
 * 1. 登录响应改用 `Response<ApiResponse<LoginResponse>>` 解包，失败时抛出的
 *    [ApiException] 已带后端中文文案（如"用户名或密码错误"），不再是英文 reason phrase。
 * 2. 登录成功后**完整落盘** `refreshToken` / `expiresIn` / `roles` / `perms`，
 *    改造前只存了 `token`，导致令牌刷新和按钮级权限根本没有数据可用。
 * 3. 登出**必须先打服务端** `POST user/logout`，见 [logout] 的说明。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val userApi: UserApi,
    private val tokenManager: TokenManager
) {

    /**
     * 买家登录。校验 `userType == 1`，非买家账号直接拒绝。
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 成功时携带 [LoginResponse]；失败时携带带中文文案的 [ApiException]
     */
    suspend fun login(username: String, password: String): Result<LoginResponse> = apiCall {
        val data = userApi.login(LoginRequest(username, password)).requireData()

        // 身份校验放在落盘之前：避免卖家账号在买家 App 里留下半套登录态
        if (data.userType != USER_TYPE_BUYER) {
            throw ApiException(
                httpCode = ApiException.HTTP_NONE,
                message = "该账号非买家身份，请使用卖家版 App",
                fromServer = false
            )
        }

        persist(data)
        data
    }

    /**
     * 退出登录。
     *
     * ## 为什么必须先调服务端接口
     * 后端 `POST user/logout` 会把当前 access token 的 jti 写进 Redis 黑名单。
     * 只做本地 `clearAll()` 的话，那枚 token 在服务端**依旧有效**直到自然过期——
     * 任何抓到它的人都能继续以该用户身份调接口。这是安全问题，不是体验问题。
     *
     * ## 为什么服务端失败也要清本地
     * 断网 / 服务端 500 时不能把用户卡在已登录状态里出不去。
     * 服务端调用包在独立的 try 里，失败只吞掉，本地状态照清。
     */
    suspend fun logout(): Result<Unit> = apiCall {
        try {
            userApi.logout().requireSuccess()
        } catch (e: Throwable) {
            // 服务端登出失败（断网 / 网关不可用）不阻断本地登出流程。
            // 代价是这枚 token 没进黑名单，但它仍会自然过期。
        }
        tokenManager.clearAll()
    }

    fun isLoggedIn(): Boolean = tokenManager.isLoggedIn

    /**
     * 把登录响应完整写入本地。
     *
     * @param data 登录响应
     */
    private fun persist(data: LoginResponse) {
        tokenManager.saveTokens(
            accessToken = data.token,
            refreshToken = data.refreshToken,
            expiresIn = data.expiresIn
        )
        tokenManager.userId = data.userId
        tokenManager.userType = data.userType
        tokenManager.username = data.username
        tokenManager.roles = data.roles
        tokenManager.perms = data.perms
    }

    companion object {
        /** 买家 */
        const val USER_TYPE_BUYER = 1
    }
}
