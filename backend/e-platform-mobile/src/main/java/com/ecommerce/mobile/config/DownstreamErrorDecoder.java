package com.ecommerce.mobile.config;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Feign 下游错误响应解码器 —— 把下游微服务的错误语义翻译回 mobile BFF 的业务异常。
 *
 * <h3>它修的是什么</h3>
 * <p>T03d 把 user / product / order 的错误响应从「HTTP 200 + code 500」改造成了
 * <b>真实 HTTP 状态码</b>（404 商品不存在、403 无权操作、400 参数不合法……）。
 * 但 mobile BFF 的 Feign 客户端此前未配置任何 {@link ErrorDecoder}，走的是
 * {@code ErrorDecoder.Default}：非 2xx 一律抛 {@code FeignException}，
 * <b>根本不会返回 {@code Result}</b>。于是：
 * <ol>
 *   <li>各 Service 里的 {@code if (!result.isSuccess())} 分支对 4xx 变成死代码；</li>
 *   <li>{@code FeignException} 一路穿透到 {@code GlobalExceptionHandler} 的兜底分支；</li>
 *   <li>调用方收到 {@code HTTP 200 + code 500「系统异常，请联系管理员」}。</li>
 * </ol>
 * 同一个业务语义，直连是「商品不存在 / 404」，走 BFF 就成了「系统异常」——
 * 比改造前更糟，因为改造前至少文案是准的。
 *
 * <h3>处理策略</h3>
 * <table border="1">
 *   <tr><th>下游响应</th><th>本解码器抛出</th><th>mobile 对外</th></tr>
 *   <tr><td>4xx + 标准 {@code Result} 信封</td><td>{@code BusinessException(信封.code, 信封.message)}</td>
 *       <td>与直连逐字一致</td></tr>
 *   <tr><td>4xx + 非标准 / 空 / 非 JSON body</td><td>{@code BusinessException(HTTP 状态, 状态默认文案)}</td>
 *       <td>状态正确、文案通用</td></tr>
 *   <tr><td>5xx（下游真的挂了）</td><td>{@code BusinessException(503, 固定文案)}</td>
 *       <td>HTTP 503，与业务软失败区分开</td></tr>
 *   <tr><td>其它非 2xx（如未跟随的 3xx）</td><td>{@code BusinessException(503, 固定文案)}</td>
 *       <td>HTTP 503</td></tr>
 * </table>
 *
 * <h3>为什么 5xx 不原样透传</h3>
 * <p>下游真的 500，说明是<b>服务端内部故障</b>。若原样透传成 {@code code 500}，
 * 会被 {@code GlobalExceptionHandler} 的白名单判定为「业务软失败」而返回
 * <b>HTTP 200</b>——「下游挂了」和「业务规则拒绝」就此混为一谈，监控再也分不出
 * 哪条是真事故。这里统一映射为 {@link ErrorCode#SERVICE_UNAVAILABLE}（503），
 * 该码的语义本就定义为「下游不可达」，且已在白名单内，会落地成真实 HTTP 503。
 *
 * <p>同时<b>刻意不回吐下游的 5xx 文案</b>：下游内部错误消息可能含类名、SQL 片段、
 * 主机名等内部信息，回吐给终端等于免费送一份内部结构图。原文只进服务端日志。
 *
 * <h3>健壮性契约</h3>
 * <p><b>本解码器自身绝不抛出异常。</b>{@link #decode} 的整个方法体被兜底
 * {@code catch (Throwable)} 包住——解析失败、流读取失败、甚至 {@code response}
 * 为 {@code null}，都只会退化成一个 503 的 {@link BusinessException}。
 * 解码器里再抛一个新异常，只会让原始故障彻底消失在调用栈里，把问题变得更难查。
 */
public class DownstreamErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(DownstreamErrorDecoder.class);

    /** 下游不可用时对外的固定文案，刻意不含下游内部细节。 */
    static final String UNAVAILABLE_MESSAGE = "服务暂时不可用，请稍后重试";

    /** 错误响应体最大读取字节数，防止下游返回超大 body 撑爆 BFF 内存。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    /** 写入日志的响应体最大字符数，防止 HTML 错误页刷屏。 */
    private static final int MAX_LOG_BODY_LENGTH = 500;

    /** 读流缓冲区大小。 */
    private static final int READ_CHUNK_SIZE = 4096;

    /** HTTP 4xx 区间下界（含）。 */
    private static final int CLIENT_ERROR_MIN = 400;

    /** HTTP 5xx 区间下界（含），同时是 4xx 区间上界（不含）。 */
    private static final int SERVER_ERROR_MIN = 500;

    /** JSON 解析器，复用 Spring 容器里的实例以保持配置一致。 */
    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper Spring 容器的 Jackson 实例；为 {@code null} 时自建一个默认实例，
     *                     保证解码器在任何装配方式下都可用
     */
    public DownstreamErrorDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * 把下游的非 2xx 响应翻译为 {@link BusinessException}。
     *
     * @param methodKey Feign 方法标识，形如 {@code ProductClient#getProductById(Long)}
     * @param response  下游响应，理论上非 {@code null}，但仍按可能为 {@code null} 防御
     * @return 待抛出的异常，<b>永远非 {@code null}</b>
     */
    @Override
    public Exception decode(String methodKey, Response response) {
        try {
            return decodeInternal(methodKey, response);
        } catch (Throwable t) {
            // 解码器自身出问题时，绝不允许把新异常抛给调用栈——那会掩盖真正的下游故障。
            log.error("下游错误响应解码失败，已降级为 503：methodKey={}", methodKey, t);
            return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * 解码主流程。
     *
     * @param methodKey Feign 方法标识
     * @param response  下游响应
     * @return 业务异常
     */
    private Exception decodeInternal(String methodKey, Response response) {
        int status = response.status();
        String rawBody = readBody(response);

        if (status >= SERVER_ERROR_MIN || status < CLIENT_ERROR_MIN) {
            // 5xx = 下游内部故障；< 400 = 不该走到 ErrorDecoder 的异常情形（如未跟随的 3xx）。
            // 两者都不是「业务拒绝」，一律按下游不可用处理，原始 body 只进日志。
            log.error("下游服务异常：methodKey={}, httpStatus={}, body={}",
                    methodKey, status, truncate(rawBody));
            return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }

        Envelope envelope = parseEnvelope(rawBody);
        int code = resolveCode(status, envelope);
        String message = resolveMessage(status, envelope);

        if (status == ErrorCode.UNAUTHORIZED) {
            // 401 有两个来源，日志里必须把它们点破，否则排查方向会被带偏：
            //   a) 终端凭证问题：user 服务的「用户名或密码错误」等，透传 401 是正确的；
            //   b) 内部信任链问题：下游 GatewaySignatureInterceptor 的「非法请求来源」，
            //      根因是 mobile 的 internal.token 与下游不一致——此时终端看到的 401
            //      是误导性的，但该故障会让「所有」下游调用同时 401，特征极明显。
            log.warn("下游返回 401：methodKey={}, message={}；"
                            + "若所有下游调用同时 401，请优先排查 internal.token 是否与下游一致"
                            + "（下游文案会是「非法请求来源」），而非终端登录态",
                    methodKey, message);
        } else {
            log.warn("下游业务拒绝：methodKey={}, httpStatus={}, code={}, message={}",
                    methodKey, status, code, message);
        }
        return new BusinessException(code, message);
    }

    /**
     * 解析业务错误码。
     *
     * <p><b>不变式：下游的 4xx 绝不允许被降级成 HTTP 200 软失败。</b>
     * 因此只有当信封里的 {@code code} 本身也落在 4xx 区间时才采信它；
     * 其余情况（缺失、非数字、越界、4xx 响应里却写着 5xx）一律以 HTTP 状态为准。
     * 对本平台自己的服务，{@code GlobalExceptionHandler} 保证了
     * {@code HTTP 状态 == body.code}，两者本就一致。
     *
     * @param status   下游 HTTP 状态码，调用前已确保落在 [400, 500)
     * @param envelope 解析出的信封，允许为 {@code null}
     * @return 业务错误码
     */
    private static int resolveCode(int status, Envelope envelope) {
        if (envelope == null || envelope.code == null) {
            return status;
        }
        int code = envelope.code;
        if (code >= CLIENT_ERROR_MIN && code < SERVER_ERROR_MIN) {
            return code;
        }
        return status;
    }

    /**
     * 解析对外文案：优先用信封里的业务文案，缺失时退回按状态码的通用文案。
     *
     * @param status   下游 HTTP 状态码
     * @param envelope 解析出的信封，允许为 {@code null}
     * @return 非空文案
     */
    private static String resolveMessage(int status, Envelope envelope) {
        if (envelope != null && hasText(envelope.message)) {
            return envelope.message;
        }
        return defaultMessage(status);
    }

    /**
     * 解析下游响应体中的 {@code Result} 信封。
     *
     * <p><b>只认「带数字 code 字段的 JSON 对象」</b>。这是区分「本平台的 Result」与
     * 「外来错误格式」的判据：Spring 默认错误页是
     * {@code {"timestamp":...,"status":404,"error":"Not Found","path":"/product/9"}}，
     * 网关也可能有自己的格式。对这些外来 body，本方法返回 {@code null}，
     * 由调用方按 HTTP 状态兜底——<b>刻意不去读它们的 error / path / message</b>，
     * 否则会把下游的内部路径与异常原文透给终端。
     *
     * @param rawBody 原始响应体，允许为 {@code null}
     * @return 信封；无法识别时返回 {@code null}
     */
    private Envelope parseEnvelope(String rawBody) {
        if (!hasText(rawBody)) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("下游错误响应不是合法 JSON，按 HTTP 状态兜底：body={}", truncate(rawBody));
            return null;
        }
        if (node == null || !node.isObject()) {
            log.warn("下游错误响应不是 JSON 对象，按 HTTP 状态兜底：body={}", truncate(rawBody));
            return null;
        }
        JsonNode codeNode = node.get("code");
        if (codeNode == null || !codeNode.isNumber()) {
            log.warn("下游错误响应非本平台 Result 信封，按 HTTP 状态兜底：body={}", truncate(rawBody));
            return null;
        }
        JsonNode messageNode = node.get("message");
        String message = messageNode != null && messageNode.isTextual() ? messageNode.asText() : null;
        return new Envelope(codeNode.asInt(), message);
    }

    /**
     * 有界地读取响应体，任何 IO 异常都吞掉并返回 {@code null}。
     *
     * @param response 下游响应
     * @return 响应体文本；无 body 或读取失败时返回 {@code null}
     */
    private static String readBody(Response response) {
        Response.Body body = response.body();
        if (body == null) {
            return null;
        }
        Charset charset = response.charset() == null ? StandardCharsets.UTF_8 : response.charset();
        try (InputStream in = body.asInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[READ_CHUNK_SIZE];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                int allowed = Math.min(read, MAX_BODY_BYTES - total);
                if (allowed > 0) {
                    buffer.write(chunk, 0, allowed);
                    total += allowed;
                }
                if (total >= MAX_BODY_BYTES) {
                    break;
                }
            }
            return new String(buffer.toByteArray(), charset);
        } catch (IOException e) {
            log.warn("读取下游错误响应体失败，按 HTTP 状态兜底：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 按 HTTP 状态给出通用中文文案。
     *
     * @param status HTTP 状态码
     * @return 通用文案
     */
    private static String defaultMessage(int status) {
        switch (status) {
            case ErrorCode.BAD_REQUEST:
                return "请求参数不合法";
            case ErrorCode.UNAUTHORIZED:
                return "身份认证失败，请重新登录";
            case ErrorCode.FORBIDDEN:
                return "无权限执行该操作";
            case ErrorCode.NOT_FOUND:
                return "资源不存在";
            case ErrorCode.METHOD_NOT_ALLOWED:
                return "请求方法不被支持";
            case ErrorCode.REQUEST_TIMEOUT:
                return "请求超时，请稍后重试";
            case ErrorCode.CONFLICT:
                return "资源状态冲突，请刷新后重试";
            case ErrorCode.TOO_MANY_REQUESTS:
                return "请求过于频繁，请稍后重试";
            default:
                return "请求无法被处理";
        }
    }

    /**
     * @param text 待判断字符串，允许为 {@code null}
     * @return 非 {@code null} 且去除空白后非空时返回 {@code true}
     */
    private static boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    /**
     * 截断写入日志的响应体。
     *
     * @param body 响应体，允许为 {@code null}
     * @return 截断后的文本；{@code null} 归一为 {@code "<empty>"}
     */
    private static String truncate(String body) {
        if (body == null) {
            return "<empty>";
        }
        if (body.length() <= MAX_LOG_BODY_LENGTH) {
            return body;
        }
        return body.substring(0, MAX_LOG_BODY_LENGTH) + "...(truncated)";
    }

    /**
     * 下游 {@code Result} 信封中本解码器关心的两个字段。
     */
    private static final class Envelope {

        /** 业务错误码，来自 {@code Result.code}。 */
        private final Integer code;

        /** 业务文案，来自 {@code Result.message}，允许为 {@code null}。 */
        private final String message;

        private Envelope(Integer code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}
