package com.ecommerce.gateway.queue;

/**
 * 轮询接口 {@code GET /api/queue/status} 的判定结果。
 *
 * <p>不可变值对象。刻意<b>不携带任何用户身份字段</b>：该接口无鉴权，
 * 结果对象里一旦出现 userId/username，就等于开了一个凭 token 即可读取身份的口子。
 *
 * <p>与 {@link QueueVerdict} 分开定义而不是复用：{@code QueueVerdict} 表达的是
 * 「转发管线放不放行」，本类表达的是「排队进度」，两者的生命周期和消费者完全不同，
 * 强行合并会让 {@code QueueVerdict} 长出一堆代理管线用不上的字段。
 */
public final class QueuePollResult {

    /** 轮询状态。 */
    public enum State {
        /** 已持有名额，客户端可以带票据重发原请求。 */
        READY,
        /** 仍在等待队列中。 */
        WAITING,
        /** 票据不存在、已超时或已被消费。 */
        EXPIRED
    }

    private final State state;
    private final String token;
    private final int position;
    private final int estimatedWaitSeconds;
    private final int pollInterval;

    private QueuePollResult(State state, String token, int position,
                            int estimatedWaitSeconds, int pollInterval) {
        this.state = state;
        this.token = token;
        this.position = position;
        this.estimatedWaitSeconds = estimatedWaitSeconds;
        this.pollInterval = pollInterval;
    }

    /**
     * 构造「已就绪」结果。
     *
     * @param token 排队票据
     * @return 就绪结果
     */
    public static QueuePollResult ready(String token) {
        return new QueuePollResult(State.READY, token == null ? "" : token, 0, 0, 0);
    }

    /**
     * 构造「仍在排队」结果。
     *
     * @param token                排队票据
     * @param position             1 起算的队列位次（Redis 的 ZRANK 是 0 起算，转换在调用方完成）
     * @param estimatedWaitSeconds 预计等待秒数
     * @param pollInterval         建议轮询间隔（秒）
     * @return 等待结果
     */
    public static QueuePollResult waiting(String token, int position,
                                          int estimatedWaitSeconds, int pollInterval) {
        return new QueuePollResult(State.WAITING, token == null ? "" : token,
                position, estimatedWaitSeconds, pollInterval);
    }

    /**
     * 构造「票据失效」结果。
     *
     * <p>不区分「票据从未存在」与「票据已超时」：对客户端来说处理动作完全一样
     * （重新提交原请求），区分开只会给攻击者提供枚举票据的旁路信号。
     *
     * @return 失效结果
     */
    public static QueuePollResult expired() {
        return new QueuePollResult(State.EXPIRED, "", 0, 0, 0);
    }

    public State getState() {
        return state;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    public boolean isExpired() {
        return state == State.EXPIRED;
    }

    public String getToken() {
        return token;
    }

    public int getPosition() {
        return position;
    }

    public int getEstimatedWaitSeconds() {
        return estimatedWaitSeconds;
    }

    public int getPollInterval() {
        return pollInterval;
    }

    @Override
    public String toString() {
        return "QueuePollResult{" + state + ", position=" + position + '}';
    }
}
