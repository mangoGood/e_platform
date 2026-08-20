package com.ecommerce.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * 全局共享的 kotlinx.serialization 配置。
 *
 * 后端 `Result` / 各 VO 的字段会随迭代增减，因此必须显式打开 [Json.ignoreUnknownKeys]：
 * kotlinx.serialization 与 Moshi/Gson 不同，**默认对未知字段直接抛异常**，
 * 不显式配置就会在后端加字段的那天全线反序列化失败。
 *
 * - [Json.ignoreUnknownKeys]：忽略后端新增的未知字段
 * - [Json.explicitNulls]：序列化时省略 null 字段，避免把 null 当成"显式清空"传给后端
 * - [Json.coerceInputValues]：后端传 null 给非空字段时回落到默认值，而不是抛异常
 * - [Json.isLenient]：容忍不带引号的字面量
 */
object JsonConfig {

    // explicitNulls 目前仍标注为实验性 API。它的语义（序列化时省略 null）
    // 在 kotlinx.serialization 1.x 内是稳定的，这里显式 opt-in 而不是绕开它——
    // 绕开的代价是给后端传一堆 "field": null，那才是真正的风险。
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }
}
