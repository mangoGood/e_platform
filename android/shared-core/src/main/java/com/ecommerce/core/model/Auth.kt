package com.ecommerce.core.model

import kotlinx.serialization.Serializable

/**
 * 登录请求，对应后端 `LoginRequest`
 */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

/**
 * 刷新令牌请求，对应后端 `RefreshTokenRequest`
 */
@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)

/**
 * 令牌对，对应后端 `TokenPair`（`POST user/refresh` 的返回体）。
 *
 * refreshToken 是**轮换式**的：每次刷新后端都会下发新的并立刻作废旧的。
 */
@Serializable
data class TokenPair(
    val token: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long? = null
)

/**
 * 用户档案，对应后端 `UserProfile`（登录响应里的 `user` 字段）。
 */
@Serializable
data class UserProfile(
    val id: Long? = null,
    val username: String? = null,
    val userType: Int? = null,
    val avatar: String? = null,
    val roles: List<String> = emptyList(),
    val perms: List<String> = emptyList()
)

/**
 * 登录响应，对应后端 `com.ecommerce.user.dto.LoginResponse`。
 *
 * 改造前只声明了 `token/userId/username/userType/avatar` 五个字段，
 * 后端实际还会下发 `refreshToken`、`expiresIn` 和 `user{roles,perms}`，
 * 缺了它们就没法做令牌刷新和按钮级权限控制。
 *
 * 全部给默认值：后端字段增减时不会因为"必填字段缺失"直接反序列化失败。
 */
@Serializable
data class LoginResponse(
    val token: String = "",
    val userId: Long = 0L,
    val username: String = "",
    val userType: Int = 0,
    val avatar: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long? = null,
    val user: UserProfile? = null
) {
    /** 角色列表，优先取 `user.roles` */
    val roles: List<String> get() = user?.roles ?: emptyList()

    /** 权限点列表，优先取 `user.perms` */
    val perms: List<String> get() = user?.perms ?: emptyList()
}
