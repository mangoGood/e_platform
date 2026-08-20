package com.ecommerce.core.network

import com.ecommerce.core.model.ApiResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 统一错误映射器：把 HTTP 状态码 + errorBody 翻译成**用户可读的中文文案**。
 *
 * ## 为什么必须解析 errorBody
 * 后端所有异常都走 `Result` 统一包装，响应体形如：
 * ```json
 * {"code":401,"message":"用户名或密码错误","data":null,"success":false}
 * ```
 * 而 Retrofit 的 `Response.message()` 拿到的是 HTTP reason phrase（"Unauthorized" /
 * "Forbidden" 这类英文串），把它丢给用户等于什么都没说。
 * 所以 [fromErrorResponse] **优先从 errorBody 里解析 `message` 字段**，
 * 只有解析不出来（响应体为空 / 不是 JSON / 被网关改写成 HTML 错误页）时才退回
 * [defaultMessage] 的状态码兜底文案。
 */
object ErrorMapper {

    /** HTTP 状态码 -> 兜底中文文案。仅在 errorBody 解析失败时使用。 */
    private val FALLBACK_BY_HTTP_CODE: Map<Int, String> = mapOf(
        400 to "请求参数有误，请检查后重试",
        401 to "登录已过期，请重新登录",
        403 to "没有权限执行此操作",
        404 to "资源不存在",
        408 to "排队超时，请重新提交",
        409 to "操作冲突，请刷新后重试",
        429 to "操作过于频繁，请稍后再试",
        500 to "服务异常，请稍后重试",
        502 to "服务异常，请稍后重试",
        503 to "服务暂时不可用，请稍后再试",
        504 to "服务响应超时，请稍后重试"
    )

    /** 网络层（拿不到 HTTP 响应）统一文案 */
    const val NETWORK_ERROR_MESSAGE: String = "网络连接失败，请检查网络"

    /** 兜底文案：状态码没有专门映射时使用 */
    private const val GENERIC_ERROR_MESSAGE: String = "操作失败，请稍后重试"

    /**
     * 按 HTTP 状态码取兜底中文文案。
     *
     * @param httpCode HTTP 状态码
     * @return 中文文案，永不为空
     */
    fun defaultMessage(httpCode: Int): String =
        FALLBACK_BY_HTTP_CODE[httpCode] ?: GENERIC_ERROR_MESSAGE

    /**
     * 从后端错误响应体里解析 `message` 字段。
     *
     * 用 [kotlinx.serialization.json.Json.parseToJsonElement] 而不是反序列化成
     * `ApiResponse<Unit>`：错误响应的 `data` 结构不可控（可能是对象也可能是数组），
     * 按具体类型解会二次失败，把真正的中文文案也弄丢。
     *
     * @param rawBody 原始响应体文本，可为 null
     * @return 解析出的非空 message；解析失败或字段缺失时返回 null
     */
    fun parseServerMessage(rawBody: String?): String? {
        if (rawBody.isNullOrBlank()) return null
        return try {
            val root = JsonConfig.json.parseToJsonElement(rawBody) as? JsonObject ?: return null
            val messageNode = root["message"] as? JsonPrimitive ?: return null
            // isString 过滤掉 "message":null 这种被解析成 JsonNull 的情况
            if (!messageNode.isString) return null
            messageNode.content.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // 响应体不是合法 JSON（例如网关直接吐了 HTML 错误页），走兜底文案
            null
        }
    }

    /**
     * 从后端响应体里解析业务 `code` 字段。
     *
     * @param rawBody 原始响应体文本
     * @return 业务码；解析失败返回 null
     */
    private fun parseServerCode(rawBody: String?): Int? {
        if (rawBody.isNullOrBlank()) return null
        return try {
            val root = JsonConfig.json.parseToJsonElement(rawBody) as? JsonObject ?: return null
            val codeNode = root["code"] as? JsonPrimitive ?: return null
            codeNode.content.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 把一个失败的 Retrofit [Response] 转成 [ApiException]。
     *
     * @param response 非 2xx 的响应
     * @return 带中文文案的异常
     */
    fun fromErrorResponse(response: Response<*>): ApiException {
        val rawBody: String? = try {
            response.errorBody()?.string()
        } catch (e: IOException) {
            null
        }
        return fromErrorBody(response.code(), rawBody)
    }

    /**
     * 把状态码 + 原始响应体转成 [ApiException]。抽出来是为了让单测/Interceptor 复用。
     *
     * @param httpCode HTTP 状态码
     * @param rawBody  原始响应体文本
     * @return 带中文文案的异常
     */
    fun fromErrorBody(httpCode: Int, rawBody: String?): ApiException {
        val serverMessage = parseServerMessage(rawBody)
        val serverCode = parseServerCode(rawBody)
        return ApiException(
            httpCode = httpCode,
            message = serverMessage ?: defaultMessage(httpCode),
            bizCode = serverCode,
            fromServer = serverMessage != null
        )
    }

    /**
     * 把 HTTP 200 但业务失败（`code != 200`）的响应转成 [ApiException]。
     *
     * @param httpCode HTTP 状态码（通常是 200）
     * @param body     已反序列化的响应体
     * @return 带中文文案的异常
     */
    fun fromBusinessFailure(httpCode: Int, body: ApiResponse<*>): ApiException {
        val serverMessage = body.message?.takeIf { it.isNotBlank() }
        val bizCode = body.code
        return ApiException(
            httpCode = httpCode,
            message = serverMessage ?: defaultMessage(bizCode ?: httpCode),
            bizCode = bizCode,
            fromServer = serverMessage != null
        )
    }

    /**
     * 把任意异常归一化成 [ApiException]，保证 UI 层拿到的 `message` 一定是中文。
     *
     * @param throwable 原始异常
     * @return 归一化后的异常
     */
    fun toApiException(throwable: Throwable): ApiException = when (throwable) {
        is ApiException -> throwable

        is HttpException -> fromErrorBody(
            httpCode = throwable.code(),
            rawBody = try {
                throwable.response()?.errorBody()?.string()
            } catch (e: IOException) {
                null
            }
        )

        is UnknownHostException,
        is ConnectException,
        is SocketTimeoutException,
        is SSLException -> ApiException(
            httpCode = ApiException.HTTP_NONE,
            message = NETWORK_ERROR_MESSAGE,
            cause = throwable
        )

        is IOException -> ApiException(
            httpCode = ApiException.HTTP_NONE,
            message = NETWORK_ERROR_MESSAGE,
            cause = throwable
        )

        else -> ApiException(
            httpCode = ApiException.HTTP_NONE,
            message = throwable.message?.takeIf { it.isNotBlank() } ?: GENERIC_ERROR_MESSAGE,
            cause = throwable
        )
    }
}

// ---------------------------------------------------------------------------
// 统一的响应解包扩展
//
// 改造前：ProductDetailRepository / OrderRepository / CartRepository /
//         CheckoutRepository / seller ProductRepository / SellerOrderRepository
//         这 6 个文件各自抄了一份逐字符相同的 `private fun <T> ApiResponse<T>.requireData()`。
// 改造后：全部收敛到下面这两个扩展，6 份重复实现删除。
// ---------------------------------------------------------------------------

/**
 * 校验响应是否成功（HTTP 2xx 且业务 `code == 200`），不关心 `data`。
 *
 * 适用于删除/更新这类返回 `ApiResponse<Unit>` 的接口——它们的 `data` 恒为 null，
 * 用 [requireData] 会误判成"数据为空"。
 *
 * @throws ApiException HTTP 失败或业务失败时抛出，`message` 为中文文案
 */
fun <T> Response<ApiResponse<T>>.requireSuccess() {
    ensureSuccessBody()
}

/**
 * 校验响应成功并返回非空 `data`。
 *
 * @return 响应体的 `data` 字段
 * @throws ApiException HTTP 失败、业务失败或 `data` 为空时抛出，`message` 为中文文案
 */
fun <T : Any> Response<ApiResponse<T>>.requireData(): T {
    val body = ensureSuccessBody()
    return body?.data ?: throw ApiException(
        httpCode = code(),
        message = "数据为空",
        bizCode = body?.code,
        fromServer = false
    )
}

/**
 * 校验响应成功并返回 `data`，允许为空。
 *
 * @return 响应体的 `data` 字段，可能为 null
 * @throws ApiException HTTP 失败或业务失败时抛出
 */
fun <T : Any> Response<ApiResponse<T>>.dataOrNull(): T? = ensureSuccessBody()?.data

/**
 * 统一的成功性校验：先看 HTTP 状态码，再看业务 `code`。
 *
 * 204/205 这类空响应体是合法成功，此时 `body()` 为 null，直接返回 null 而不是报错。
 *
 * @return 反序列化后的响应体；空响应体时为 null
 * @throws ApiException 失败时抛出
 */
private fun <T> Response<ApiResponse<T>>.ensureSuccessBody(): ApiResponse<T>? {
    if (!isSuccessful) {
        throw ErrorMapper.fromErrorResponse(this)
    }
    val body = body() ?: return null
    // code 为 null 时视为成功（部分接口/网关直出的响应体没有 code 字段）
    if (body.code != null && body.code != 200) {
        throw ErrorMapper.fromBusinessFailure(code(), body)
    }
    return body
}

/**
 * Repository 层统一的调用包装：把任何异常归一化成带中文文案的 [ApiException]。
 *
 * 替代裸 `runCatching {}`——后者会把 OkHttp 的英文 IOException 原样透给 UI。
 *
 * @param block 实际的网络调用
 * @return 成功时为 [Result.success]，失败时为携带 [ApiException] 的 [Result.failure]
 */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: Throwable) {
    Result.failure(ErrorMapper.toApiException(e))
}
