package com.ecommerce.core.model

import kotlinx.serialization.Serializable

/**
 * 登录请求，对应后端 LoginRequest
 */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

/**
 * 登录响应，对应后端 LoginResponse
 */
@Serializable
data class LoginResponse(
    val token: String,
    val userId: Long,
    val username: String,
    val userType: Int,
    val avatar: String? = null
)
