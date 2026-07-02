package com.ecommerce.core.network

import com.ecommerce.core.datastore.TokenManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 认证拦截器：自动注入 JWT Token；401 时清除登录态
 */
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenManager.token
        val request = if (!token.isNullOrEmpty()) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }

        val response = chain.proceed(request)

        // 401 表示 token 失效，清除本地登录态
        if (response.code == 401) {
            tokenManager.clear()
        }
        return response
    }
}
