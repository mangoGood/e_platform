package com.ecommerce.core.model

import kotlinx.serialization.Serializable

/**
 * 后端统一响应体，对应 com.ecommerce.common.result.Result
 */
@Serializable
data class ApiResponse<T>(
    val code: Int? = null,
    val message: String? = null,
    val data: T? = null
) {
    val isSuccess: Boolean get() = code == 200
}
