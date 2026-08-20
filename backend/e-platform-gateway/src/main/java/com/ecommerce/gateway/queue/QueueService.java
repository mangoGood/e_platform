package com.ecommerce.gateway.queue;

import com.ecommerce.gateway.security.AuthPrincipal;

/**
 * 秒杀排队守卫（网关侧）。
 *
 * <p><b>本接口由 T02 定义、T03 实现。</b>T02 只提供 {@link NoopQueueService} 空实现，
 * 保证安全管线的调用点和 {@code finally} 归还逻辑此刻就是正确的，
 * T03 落地 Redis Lua 版本时只需新增一个实现 Bean，管线代码一行不用改。
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li>{@link #acquire} <b>不得抛异常</b>。Redis 不可用时应降级为本地信号量或直接放行，
 *       排队是流控手段，不能变成新的可用性单点。</li>
 *   <li>{@link #release} 必须<b>幂等</b>：管线在 {@code finally} 中调用，
 *       转发抛异常时同样会执行；重复归还同一票据不得导致名额虚增。</li>
 *   <li>只对受保护的写路径生效（如 {@code POST /order/create}），其余路径应立刻返回
 *       {@link QueueVerdict#PASS}，避免给全站请求增加一次 Redis 往返。</li>
 * </ul>
 */
public interface QueueService {

    /**
     * 尝试为本次请求获取处理名额。
     *
     * @param path      已剥离 {@code /api} 前缀的请求路径，如 {@code /order/create}
     * @param method    HTTP 方法，大写
     * @param principal 网关认定的身份，永不为 null（匿名为 {@link AuthPrincipal#ANONYMOUS}）
     * @return 放行或直接应答的判定结果，永不为 null
     */
    QueueVerdict acquire(String path, String method, AuthPrincipal principal);

    /**
     * 归还名额。由管线在 {@code finally} 中无条件调用。
     *
     * @param verdict {@link #acquire} 的返回值，可能为 null 或不含票据，实现需自行容错
     */
    void release(QueueVerdict verdict);
}
