package com.ecommerce.core.network

import java.io.IOException

/**
 * 统一的业务/网络异常。
 *
 * 继承 [IOException] 而不是 RuntimeException，是为了能在 OkHttp 的
 * Interceptor / Authenticator 里直接抛出——OkHttp 只允许 Interceptor 抛 IOException，
 * 抛别的类型会被包装成 `Undeliverable exception` 并丢失原始文案。
 *
 * @property httpCode HTTP 状态码；纯网络异常（连不上）时为 [HTTP_NONE]
 * @property message  **已经本地化的中文文案**，可直接展示给用户
 * @property bizCode  后端响应体里的 `code` 字段，可能与 [httpCode] 不同
 * @property fromServer 文案是否来自后端 errorBody 的 `message` 字段。
 *                      false 表示走了本地兜底映射，便于排查"为什么用户看到的是通用文案"
 */
class ApiException(
    val httpCode: Int,
    override val message: String,
    val bizCode: Int? = null,
    val fromServer: Boolean = false,
    cause: Throwable? = null
) : IOException(message, cause) {

    /** 是否为需要重新登录的鉴权失败 */
    val isUnauthorized: Boolean get() = httpCode == 401 || bizCode == 401

    override fun toString(): String =
        "ApiException(httpCode=$httpCode, bizCode=$bizCode, fromServer=$fromServer, message=$message)"

    companion object {
        /** 没有拿到 HTTP 响应（DNS/连接/超时等） */
        const val HTTP_NONE: Int = -1
    }
}
