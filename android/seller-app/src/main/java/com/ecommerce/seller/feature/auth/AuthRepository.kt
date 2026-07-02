package com.ecommerce.seller.feature.auth

import com.ecommerce.core.datastore.TokenManager
import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.LoginRequest
import com.ecommerce.core.model.LoginResponse
import com.ecommerce.core.network.UserApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val userApi: UserApi,
    private val tokenManager: TokenManager
) {
    /**
     * 卖家登录：校验 userType=2
     */
    suspend fun login(username: String, password: String): Result<LoginResponse> {
        return try {
            val response: ApiResponse<LoginResponse> = userApi.login(LoginRequest(username, password))
            val data = response.data
            if (response.isSuccess && data != null) {
                if (data.userType != 2) {
                    return Result.failure(IllegalAccessException("该账号非卖家身份，请使用买家版 App"))
                }
                tokenManager.token = data.token
                tokenManager.userId = data.userId
                tokenManager.userType = data.userType
                tokenManager.username = data.username
                Result.success(data)
            } else {
                Result.failure(RuntimeException(response.message ?: "登录失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isLoggedIn(): Boolean = tokenManager.isLoggedIn

    fun logout() {
        tokenManager.clear()
    }
}
