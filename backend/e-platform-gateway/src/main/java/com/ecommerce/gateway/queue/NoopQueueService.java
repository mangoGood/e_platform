package com.ecommerce.gateway.queue;

import com.ecommerce.gateway.security.AuthPrincipal;

/**
 * 排队守卫的空实现：一律放行、归还为 no-op。
 *
 * <p>T02 阶段的默认 Bean（见 {@code GatewayConfig#queueService()}，用
 * {@code @ConditionalOnMissingBean} 注册）。T03 提供真实实现后本类自动让位，
 * <b>无需删除也无需改动调用方</b>。
 *
 * <p>刻意不做任何硬编码限流：T02 的职责是身份与信任链，
 * 在这里塞一个"临时限流"只会让 T03 排查双重限流。
 */
public class NoopQueueService implements QueueService {

    @Override
    public QueueVerdict acquire(String path, String method, AuthPrincipal principal) {
        return QueueVerdict.PASS;
    }

    @Override
    public void release(QueueVerdict verdict) {
        // 空实现：没有名额被占用，无需归还。
    }
}
