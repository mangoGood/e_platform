package com.ecommerce.gateway.queue;

/**
 * 排队相关响应体的手写 JSON 构造器。
 *
 * <h3>为什么手写而不用 Jackson / common 的 Result</h3>
 * 网关<b>刻意不依赖 {@code e-platform-common}</b>（见 {@code GatewayConfig} 类注释：
 * common 里的 {@code GatewaySignatureInterceptor} 是自动装配的，引入即自锁），
 * 所以拿不到统一的 {@code Result}/{@code ErrorCode}。而排队响应结构完全固定、
 * 字段只有数字和一个 UUID，用 {@code ObjectMapper} 反而要多维护几个 DTO。
 *
 * <h3>与 ProxyService 的关系</h3>
 * 输出格式与 {@code ProxyService#error()} <b>逐字节对齐</b>
 * （{@code {"code":..,"message":"..","data":..,"success":..}}），
 * 保证前端处理网关错误时不需要区分「是排队吐的还是代理吐的」。
 * 这里没有去复用 {@code ProxyService} 的私有 {@code escapeJson()} ——
 * 为了复用一个 20 行的纯函数而把 {@code ProxyService} 的方法提权为 public，
 * 会让那条安全管线多出一个对外暴露面，不划算。
 */
public final class QueueJson {

    /** 排队中：HTTP 202，语义是「已受理，还没轮到你」。 */
    public static final int CODE_QUEUED = 202;

    /** 队列已满：HTTP 503。 */
    public static final int CODE_QUEUE_FULL = 503;

    /** 票据失效 / 等待超时：HTTP 408。 */
    public static final int CODE_TIMEOUT = 408;

    /** 已就绪：HTTP 200。 */
    public static final int CODE_READY = 200;

    /** 排队接口的轮询地址，随 202 下发，免得客户端硬编码。 */
    private static final String STATUS_URL = "/api/queue/status";

    /** ASCII 控制字符上界，低于此值必须转成 \\uXXXX。 */
    private static final char CONTROL_CHAR_LIMIT = 0x20;

    private QueueJson() {
        // 工具类，禁止实例化。
    }

    /**
     * 构造 {@code acquire} 阶段「已入队」的 202 响应体。
     *
     * @param token                排队票据（服务端生成的 UUID）
     * @param position             1 起算的队列位次，用于展示
     * @param estimatedWaitSeconds 预计等待秒数
     * @param pollInterval         建议轮询间隔（秒）
     * @return JSON 文本
     */
    public static String queuedBody(String token, int position, int estimatedWaitSeconds, int pollInterval) {
        return "{\"code\":" + CODE_QUEUED
                + ",\"message\":\"" + escape("当前抢购人数较多，已为你排队") + "\""
                + ",\"data\":{"
                + "\"queueToken\":\"" + escape(token) + "\""
                + ",\"token\":\"" + escape(token) + "\""
                + ",\"position\":" + position
                + ",\"estimatedWaitSeconds\":" + estimatedWaitSeconds
                + ",\"pollInterval\":" + pollInterval
                + ",\"statusUrl\":\"" + STATUS_URL + "\""
                + "},\"success\":false}";
    }

    /**
     * 构造「队列已满」的 503 响应体。
     *
     * @return JSON 文本
     */
    public static String queueFullBody() {
        return simpleBody(CODE_QUEUE_FULL, "当前抢购人数过多，请稍后再试", false);
    }

    /**
     * 构造轮询接口「可以继续」的 200 响应体。
     *
     * <p>刻意<b>不回显任何用户身份信息</b>：该接口不做鉴权（见 {@code QueueController} 类注释），
     * 一旦回显 userId/username 就等于给了一个免鉴权的信息泄露口子。
     *
     * @param token 排队票据
     * @return JSON 文本
     */
    public static String statusReadyBody(String token) {
        return "{\"code\":" + CODE_READY
                + ",\"message\":\"" + escape("可以继续") + "\""
                + ",\"data\":{"
                + "\"ready\":true"
                + ",\"token\":\"" + escape(token) + "\""
                + ",\"queueToken\":\"" + escape(token) + "\""
                + ",\"position\":0"
                + "},\"success\":true}";
    }

    /**
     * 构造轮询接口「仍在排队」的 202 响应体。
     *
     * @param token                排队票据
     * @param position             1 起算的队列位次
     * @param estimatedWaitSeconds 预计等待秒数
     * @param pollInterval         建议轮询间隔（秒）
     * @return JSON 文本
     */
    public static String statusWaitingBody(String token, int position,
                                           int estimatedWaitSeconds, int pollInterval) {
        return "{\"code\":" + CODE_QUEUED
                + ",\"message\":\"" + escape("排队中") + "\""
                + ",\"data\":{"
                + "\"ready\":false"
                + ",\"token\":\"" + escape(token) + "\""
                + ",\"queueToken\":\"" + escape(token) + "\""
                + ",\"position\":" + position
                + ",\"estimatedWaitSeconds\":" + estimatedWaitSeconds
                + ",\"pollInterval\":" + pollInterval
                + "},\"success\":false}";
    }

    /**
     * 构造「票据失效 / 排队超时」的 408 响应体。
     *
     * @return JSON 文本
     */
    public static String statusTimeoutBody() {
        return simpleBody(CODE_TIMEOUT, "排队超时，请重新提交", false);
    }

    /**
     * 构造 {@code data} 为 null 的简单响应体。
     *
     * @param code    业务码，与 HTTP 状态保持一致
     * @param message 面向用户的中文提示
     * @param success 业务是否成功
     * @return JSON 文本
     */
    private static String simpleBody(int code, String message, boolean success) {
        return "{\"code\":" + code + ",\"message\":\"" + escape(message)
                + "\",\"data\":null,\"success\":" + success + "}";
    }

    /**
     * 转义会破坏 JSON 结构的字符。
     *
     * <p>提示语目前都是常量，但 {@code token} 理论上可以被客户端影响
     * （{@code X-Queue-Token} 是客户端可控头），因此转义是<b>必需</b>而非防御性冗余 ——
     * 尽管 {@code RedisQueueService} 已经用白名单正则卡过一道，
     * 这里再兜一层，避免将来放宽正则时把 XSS/JSON 注入带出来。
     *
     * @param text 原始文本，可为 null
     * @return 可安全嵌入 JSON 字符串字面量的文本
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < CONTROL_CHAR_LIMIT) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }
}
