package com.ecommerce.gateway.queue;

/**
 * 排队守卫的判定结果。
 *
 * <p>只有两种形态：
 * <ul>
 *   <li><b>放行</b>（{@link #isPassed()} == true）：继续走转发管线；若携带 {@code permitToken}，
 *       转发结束后必须在 {@code finally} 中调用 {@link QueueService#release(QueueVerdict)} 归还名额。</li>
 *   <li><b>直接应答</b>（{@link #isPassed()} == false）：不转发，把 {@link #getResponseStatus()} 与
 *       {@link #getResponseBody()} 原样返回给客户端（排队中 202 / 队列已满 503）。</li>
 * </ul>
 *
 * <p>本类为不可变值对象，可安全跨线程传递。
 */
public final class QueueVerdict {

    /** 无需名额的放行单例，避免高频路径反复创建对象。 */
    public static final QueueVerdict PASS = new QueueVerdict(true, null, 0, null);

    private final boolean passed;
    private final String permitToken;
    private final int responseStatus;
    private final String responseBody;

    private QueueVerdict(boolean passed, String permitToken, int responseStatus, String responseBody) {
        this.passed = passed;
        this.permitToken = permitToken;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    /**
     * 构造「已获得名额」的放行结果。
     *
     * @param permitToken 名额票据，转发结束后用于归还；为 null 表示无需归还
     * @return 放行结果
     */
    public static QueueVerdict pass(String permitToken) {
        if (permitToken == null || permitToken.isEmpty()) {
            return PASS;
        }
        return new QueueVerdict(true, permitToken, 0, null);
    }

    /**
     * 构造「不转发，直接应答」的结果。
     *
     * @param responseStatus HTTP 状态码，如 202（排队中）/ 503（队列已满）
     * @param responseBody   JSON 响应体，不可为 null
     * @return 拦截结果
     */
    public static QueueVerdict respond(int responseStatus, String responseBody) {
        return new QueueVerdict(false, null, responseStatus,
                responseBody == null ? "" : responseBody);
    }

    public boolean isPassed() {
        return passed;
    }

    /**
     * @return 名额票据；null 表示本次放行未占用名额，无需归还
     */
    public String getPermitToken() {
        return permitToken;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    @Override
    public String toString() {
        return passed
                ? "QueueVerdict{PASS, permit=" + permitToken + '}'
                : "QueueVerdict{RESPOND " + responseStatus + '}';
    }
}
