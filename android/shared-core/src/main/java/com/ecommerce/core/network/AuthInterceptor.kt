package com.ecommerce.core.network

import com.ecommerce.core.datastore.TokenManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 认证拦截器：只负责给请求注入 `Authorization` 头。
 *
 * ## 职责收窄说明
 * 改造前这里还兼职处理 401（直接 `tokenManager.clear()` 了事，既不刷新也不跳登录页）。
 * 现在 401 全部交给 [TokenAuthenticator]：
 * - 拦截器做"加头"，Authenticator 做"补凭据重发"，各司其职；
 * - 更关键的是，在拦截器里清 token 会和 Authenticator 的刷新流程抢状态——
 *   拦截器先把 token 清了，Authenticator 再去读就成了空，刷新直接失效。
 *
 * 注意：**不注入 `X-User-Id`**。该头由网关根据 JWT 注入，
 * 网关的 `HeaderSanitizer` 会清洗掉客户端伪造的同名头，客户端加了也是白加。
 */
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // 已经显式带了 Authorization（例如 Authenticator 重发的请求）就不要覆盖
        if (original.header(HEADER_AUTHORIZATION) != null) {
            return chain.proceed(original)
        }

        val token = tokenManager.accessToken
        val request = if (!token.isNullOrEmpty()) {
            original.newBuilder()
                .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }

    companion object {
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }
}
