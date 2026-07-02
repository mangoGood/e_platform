package com.ecommerce.core.model

import kotlinx.serialization.Serializable

/**
 * 分页结果，对应 com.ecommerce.common.result.PageResult
 */
@Serializable
data class PageResult<T>(
    val records: List<T> = emptyList(),
    val total: Long = 0L,
    val size: Long = 10L,
    val current: Long = 1L,
    val pages: Long = 0L
)
