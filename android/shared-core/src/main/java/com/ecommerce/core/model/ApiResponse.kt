package com.ecommerce.core.model

import kotlinx.serialization.Serializable

/**
 * 后端统一响应体，对应 `com.ecommerce.common.result.Result`。
 *
 * `Result.java` 里有 `isSuccess()` getter，Jackson 会把它序列化成 JSON 的 `success` 属性，
 * 这个字段目前稳定存在。但仍然给它默认值 `null` 而不是设成必填——
 * 后端某天调整 getter 可见性或改名时，必填字段会直接导致反序列化抛异常，
 * 可选字段则只是回落到用 `code` 判断，不影响业务。
 *
 * 未知字段的容忍由 `JsonConfig.json` 的 `ignoreUnknownKeys = true` 统一保证。
 */
@Serializable
data class ApiResponse<T>(
    val code: Int? = null,
    val message: String? = null,
    val data: T? = null,
    val success: Boolean? = null
) {
    /**
     * 业务是否成功。以 `code == 200` 为准（与后端 `Result.isSuccess()` 一致），
     * `code` 缺失时才退回看 `success` 字段。
     */
    val isSuccess: Boolean
        get() = if (code != null) code == 200 else success == true
}
