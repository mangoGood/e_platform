package com.ecommerce.core.model

import kotlinx.serialization.Serializable

/**
 * 秒杀排队票据，对应网关 `QueueJson.queuedBody()` / `statusWaitingBody()` / `statusReadyBody()`
 * 三种响应体里的 `data` 节点。
 *
 * 三种场景的字段并不完全一致，所以全部给默认值：
 * - `POST order/create` 被拦下（HTTP 202）：
 *   `{queueToken, token, position, estimatedWaitSeconds, pollInterval, statusUrl}`
 * - `GET /api/queue/status` 仍在排队（HTTP 202）：
 *   `{ready:false, token, queueToken, position, estimatedWaitSeconds, pollInterval}`
 * - `GET /api/queue/status` 已就绪（HTTP 200）：
 *   `{ready:true, token, queueToken, position}`
 */
@Serializable
data class QueueTicket(
    val queueToken: String? = null,
    val token: String? = null,
    val position: Int = 0,
    val estimatedWaitSeconds: Int = 0,
    val pollInterval: Int = DEFAULT_POLL_INTERVAL_SECONDS,
    val statusUrl: String? = null,
    val ready: Boolean = false
) {
    /** 排队票据，`queueToken` 与 `token` 是同一个值的两种写法，取任一非空的 */
    val effectiveToken: String?
        get() = queueToken?.takeIf { it.isNotBlank() } ?: token?.takeIf { it.isNotBlank() }

    /** 建议轮询间隔（秒），做下限保护避免后端下发 0 导致空转 */
    val safePollIntervalSeconds: Int
        get() = pollInterval.coerceIn(MIN_POLL_INTERVAL_SECONDS, MAX_POLL_INTERVAL_SECONDS)

    companion object {
        const val DEFAULT_POLL_INTERVAL_SECONDS = 2
        const val MIN_POLL_INTERVAL_SECONDS = 1
        const val MAX_POLL_INTERVAL_SECONDS = 10
    }
}
