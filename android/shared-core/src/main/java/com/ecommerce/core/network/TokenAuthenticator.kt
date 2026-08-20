package com.ecommerce.core.network

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 401 自动刷新：用 OkHttp 的 [Authenticator] 而不是在 Interceptor 里做。
 *
 * ## 为什么是 Authenticator 而不是 Interceptor
 * - Authenticator 是 OkHttp 为"收到 401 后补凭据重发"设计的官方钩子，
 *   由 `RetryAndFollowUpInterceptor` 驱动，**自带重试次数上限**（超过 20 次直接放弃），
 *   天然防止无限递归。
 * - 在 Interceptor 里手动 `chain.proceed()` 重发，既要自己数重试次数，
 *   又会和 OkHttp 的连接复用/重定向逻辑打架。
 *
 * ## 防递归的三道闸
 * 1. OkHttp 内置的重试上限（框架层）。
 * 2. [responseCount] 显式统计本条请求链上已经重试过几次，超过 [MAX_RETRY] 返回 null。
 * 3. **显式比对 `response.request` 上带的 Authorization 是否已经是新 token**——
 *    如果刷新出来的 token 和刚才失败用的那个一模一样，说明没有任何进展，
 *    继续重发只会得到同样的 401，直接放弃。
 */
class TokenAuthenticator(
    private val tokenRefresher: TokenRefresher
) : Authenticator {

    /**
     * 收到 401 时被 OkHttp 回调，返回带新凭据的请求以触发重发；返回 null 表示放弃。
     *
     * @param route    路由信息，未使用
     * @param response 触发 401 的响应
     * @return 重发用的新请求；不应重发时返回 null
     */
    override fun authenticate(route: Route?, response: Response): Request? {
        val failedRequest = response.request

        // 刷新接口自己返回 401 -> 绝不能再触发刷新，否则死循环。
        // （正常情况下刷新走的是裸 client，压根不会进到这里，这是双保险。）
        if (failedRequest.url.encodedPath.endsWith(REFRESH_PATH)) {
            return null
        }

        // 请求本来就没带 token（匿名接口 401），刷新也救不了。
        val staleHeader = failedRequest.header(HEADER_AUTHORIZATION) ?: return null
        val staleToken = staleHeader.removePrefix(BEARER_PREFIX).trim()
        if (staleToken.isEmpty()) return null

        // 第 2 道闸：显式重试次数上限。
        if (responseCount(response) > MAX_RETRY) return null

        // 单飞刷新：并发 401 只会真正发一次 POST user/refresh。
        return when (val result = tokenRefresher.refresh(staleToken)) {
            is RefreshResult.Success -> {
                // 第 3 道闸：新 token 必须与失败时用的那个不同，否则没有进展，放弃重发。
                if (result.accessToken == staleToken) {
                    null
                } else {
                    failedRequest.newBuilder()
                        // 用 header() 而不是 addHeader()：后者会追加出两个 Authorization 头
                        .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX${result.accessToken}")
                        .build()
                }
            }
            // 硬失败（已 clearAll + 广播强制登出）与临时失败，都不重发本次请求
            is RefreshResult.Failure -> null
            is RefreshResult.Retryable -> null
        }
    }

    /**
     * 统计本条请求链上已经产生过多少个响应（即重试了几次）。
     *
     * @param response 当前响应
     * @return 链上响应总数，首次为 1
     */
    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    companion object {
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
        private const val REFRESH_PATH = "/user/refresh"

        /** 同一条请求链最多允许 Authenticator 介入的次数 */
        private const val MAX_RETRY = 2
    }
}
