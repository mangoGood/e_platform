package com.ecommerce.seller.feature.auth

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
 * 卖家端登录态仓库。与买家端逻辑对称，仅身份校验的 `userType` 不同。
 *
 * 改造要点同买家端：完整落盘 refreshToken/expiresIn/roles/perms，
 * 登出先打服务端黑名单接口再清本地。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val userApi: UserApi,
    private val tokenManager: TokenManager
) {

    /**
     * 卖家登录。校验 `userType == 2`。
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 成功时携带 [LoginResponse]；失败时携带带中文文案的 [ApiException]
     */
    suspend fun login(username: String, password: String): Result<LoginResponse> = apiCall {
        val data = userApi.login(LoginRequest(username, password)).requireData()

        if (data.userType != USER_TYPE_SELLER) {
            throw ApiException(
                httpCode = ApiException.HTTP_NONE,
                message = "该账号非卖家身份，请使用买家版 App",
                fromServer = false
            )
        }

        persist(data)
        data
    }

    /**
     * 退出登录：先调服务端把 token 加入黑名单，再清本地。
     *
     * 服务端失败不阻断本地清理，理由见买家端同名方法的注释。
     */
    suspend fun logout(): Result<Unit> = apiCall {
        try {
            userApi.logout().requireSuccess()
        } catch (e: Throwable) {
            // 服务端登出失败不阻断本地登出
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
        /** 卖家 */
        const val USER_TYPE_SELLER = 2
    }
}
