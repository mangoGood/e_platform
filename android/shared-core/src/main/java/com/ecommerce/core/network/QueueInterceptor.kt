package com.ecommerce.core.network

import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.QueueTicket
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * 秒杀排队（HTTP 202）拦截器。
 *
 * ## 协议
 * 网关对 `POST order/create` 有排队保护（Redis ZSet，permits=50）：
 * 1. 没抢到令牌 -> 返回 **HTTP 202**，body 的 `data` 里带
 *    `{queueToken, token, position, estimatedWaitSeconds, pollInterval, statusUrl}`；
 * 2. 客户端按 `pollInterval` 轮询 `GET /api/queue/status?token=<uuid>`：
 *    - **202** 表示仍在排队（`data.ready == false`），继续轮询；
 *    - **200** 表示就绪（`data.ready == true`）；
 * 3. 就绪后带 **`X-Queue-Token`** 头重发原始请求；
 * 4. 队列满 -> **503**；票据失效/排队超时 -> **408**。
 *
 * ## 为什么必须有这个拦截器
 * Retrofit 把 2xx 一律当成功，而 202 的响应体里 `data` 是排队票据、不是订单列表，
 * 直接反序列化要么抛异常要么得到空结果——这就是"202 在 Android 侧行为是坏的"的根因。
 *
 * ## 线程模型
 * 轮询用 `Thread.sleep` 阻塞 OkHttp 工作线程。这是可接受的：
 * Retrofit 的 suspend 方法本就跑在 OkHttp 线程池上，不会阻塞主线程；
 * 并且有 [MAX_TOTAL_WAIT_MILLIS] 兜底，绝不无限等待。
 */
class QueueInterceptor : Interceptor {

    /**
     * 一次响应读取的结果：原始文本 + 解析出的票据。
     *
     * OkHttp 的 `ResponseBody.string()` 只能读一次，所以读完必须把文本留住，
     * 后面报错映射还要用它解析后端的中文 message。
     */
    private data class QueuePayload(
        val rawBody: String?,
        val ticket: QueueTicket?
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        if (response.code != HTTP_ACCEPTED) {
            return response
        }

        // 读排队票据；读不出来说明这个 202 不属于排队协议，原样放行，不要自作主张
        val payload = response.readPayload()
        val queueToken = payload.ticket?.effectiveToken
        if (queueToken.isNullOrEmpty()) {
            return response.rebuildWith(payload.rawBody)
        }
        response.close()

        return waitInQueueThenReplay(chain, originalRequest, queueToken, payload.ticket)
    }

    /**
     * 轮询排队状态，就绪后带 `X-Queue-Token` 重发原始请求。
     *
     * @param chain           拦截器链
     * @param originalRequest 被排队拦下的原始请求
     * @param queueToken      排队票据
     * @param ticket          202 响应里的票据信息，提供首个轮询间隔与 statusUrl
     * @return 重发后的真实业务响应
     * @throws ApiException 排队超时（408）、队列已满（503）或轮询接口异常时抛出
     */
    private fun waitInQueueThenReplay(
        chain: Interceptor.Chain,
        originalRequest: Request,
        queueToken: String,
        ticket: QueueTicket
    ): Response {
        val statusUrl = buildStatusUrl(originalRequest.url, ticket.statusUrl)
        val deadline = System.currentTimeMillis() + MAX_TOTAL_WAIT_MILLIS
        var pollIntervalMillis = ticket.safePollIntervalSeconds * 1000L

        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(pollIntervalMillis)

            val pollRequest = Request.Builder()
                .url(statusUrl.newBuilder().setQueryParameter(QUERY_TOKEN, queueToken).build())
                .get()
                .build()

            val pollResponse = chain.proceed(pollRequest)
            val pollCode = pollResponse.code
            val pollPayload = pollResponse.readPayload()
            pollResponse.close()

            when {
                // 就绪 -> 带票据重发原始请求，把真实业务响应交回上层
                pollCode == HTTP_OK && pollPayload.ticket?.ready == true -> {
                    return chain.proceed(
                        originalRequest.newBuilder()
                            .header(HEADER_QUEUE_TOKEN, queueToken)
                            .build()
                    )
                }
                // 仍在排队 -> 采纳后端下发的新间隔继续轮询
                pollCode == HTTP_ACCEPTED -> {
                    pollIntervalMillis = (
                        pollPayload.ticket?.safePollIntervalSeconds
                            ?: QueueTicket.DEFAULT_POLL_INTERVAL_SECONDS
                        ) * 1000L
                }
                // 408 排队超时 / 503 队列已满 / 其它异常 -> 统一转成中文文案抛出
                else -> throw ErrorMapper.fromErrorBody(pollCode, pollPayload.rawBody)
            }
        }

        throw ApiException(
            httpCode = HTTP_REQUEST_TIMEOUT,
            message = ErrorMapper.defaultMessage(HTTP_REQUEST_TIMEOUT),
            fromServer = false
        )
    }

    /**
     * 把网关下发的 `statusUrl`（形如 `/api/queue/status`）拼成完整 URL。
     *
     * 复用原始请求的 scheme/host/port，避免把网关地址硬编码进客户端。
     *
     * @param originalUrl 原始请求 URL，提供 scheme/host/port
     * @param statusPath  后端下发的路径或完整 URL，为空时用默认路径
     * @return 可直接请求的完整 URL
     */
    private fun buildStatusUrl(originalUrl: HttpUrl, statusPath: String?): HttpUrl {
        val path = statusPath?.takeIf { it.isNotBlank() } ?: DEFAULT_STATUS_PATH
        // 后端也可能下发完整 URL，先按完整 URL 试，失败再按路径拼
        path.toHttpUrlOrNull()?.let { return it }
        return HttpUrl.Builder()
            .scheme(originalUrl.scheme)
            .host(originalUrl.host)
            .port(originalUrl.port)
            .encodedPath(if (path.startsWith("/")) path else "/$path")
            .build()
    }

    /**
     * 不抛 InterruptedException 的 sleep；被中断时恢复中断标记并立刻返回。
     *
     * @param millis 睡眠毫秒数
     */
    private fun sleepQuietly(millis: Long) {
        if (millis <= 0L) return
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 读出响应体文本并尝试解析成排队票据。
     *
     * @return 原始文本与票据；解析失败时票据为 null
     */
    private fun Response.readPayload(): QueuePayload {
        val raw = try {
            body?.string()
        } catch (e: IOException) {
            null
        }
        if (raw.isNullOrBlank()) return QueuePayload(raw, null)
        val ticket = try {
            val serializer = ApiResponse.serializer(QueueTicket.serializer())
            JsonConfig.json.decodeFromString(serializer, raw).data
        } catch (e: Exception) {
            null
        }
        return QueuePayload(raw, ticket)
    }

    /**
     * 响应体已经被 [readPayload] 消费掉了，需要用缓存的文本重建一个可再次读取的响应。
     *
     * @param rawBody 已读出的响应体文本
     * @return 可继续向上层传递的响应
     */
    private fun Response.rebuildWith(rawBody: String?): Response {
        val mediaType = body?.contentType()
        val content = rawBody ?: ""
        return newBuilder()
            .body(content.toResponseBody(mediaType))
            .build()
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_ACCEPTED = 202
        const val HTTP_REQUEST_TIMEOUT = 408

        const val HEADER_QUEUE_TOKEN = "X-Queue-Token"
        const val QUERY_TOKEN = "token"
        const val DEFAULT_STATUS_PATH = "/api/queue/status"

        /** 排队总等待上限，超过按 408 处理，绝不无限等 */
        const val MAX_TOTAL_WAIT_MILLIS = 120_000L
    }
}
