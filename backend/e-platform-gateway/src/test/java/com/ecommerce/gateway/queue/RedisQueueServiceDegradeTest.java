package com.ecommerce.gateway.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RedisQueueService} 的降级路径单元测试。
 *
 * <h3>为什么只测降级路径</h3>
 * Redis 正常时的排队语义全部落在 Lua 脚本里，那部分行为已经用真实 Redis + 真实网关
 * 做过端到端自验（S1~S6）—— 用嵌入式 Redis 再测一遍只是把同样的断言换个跑法。
 * 反而是「Redis 挂了怎么办」这条路径在端到端里很难反复触发（要真去停容器，
 * 会波及同一套环境上其他人的联调），且它的正确性依赖 {@code Semaphore} 与
 * {@code localPermits} 两个内部状态的配合，正是单元测试最该覆盖的地方。
 */
class RedisQueueServiceDegradeTest {

    /** 受保护路径：注意传给 acquire 的 path 已被网关剥掉 {@code /api} 前缀。 */
    private static final String PROTECTED_PATH = "/order/create";

    private static final String PROTECTED_METHOD = "POST";

    /**
     * 构造一个 Redis 完全不可用的服务实例。
     *
     * @param permits 本地兜底名额数
     * @return 服务实例
     */
    private RedisQueueService newDegradedService(int permits) {
        QueueProperties properties = new QueueProperties();
        properties.setPermits(permits);
        properties.init();

        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        return new RedisQueueService(properties, provider);
    }

    /**
     * 反射读出内部信号量，用于断言「许可数没有被虚增」。
     *
     * <p>这个状态没有对外暴露的必要（暴露 getter 只为测试服务是一种坏味道），
     * 因此这里接受反射的代价。
     *
     * @param service 目标实例
     * @return 当前可用许可数
     */
    private int availablePermits(RedisQueueService service) throws Exception {
        Field field = RedisQueueService.class.getDeclaredField("localFallback");
        field.setAccessible(true);
        return ((Semaphore) field.get(service)).availablePermits();
    }

    @Test
    @DisplayName("非受保护路径不占名额，直接返回 PASS 单例")
    void nonProtectedPathSkipsQueue() throws Exception {
        RedisQueueService service = newDegradedService(2);

        QueueVerdict verdict = service.acquire("/product/list", "GET", null);

        assertSame(QueueVerdict.PASS, verdict, "非受保护路径不应创建新对象");
        assertNull(verdict.getPermitToken(), "非受保护路径不应占用名额");
        assertEquals(2, availablePermits(service), "非受保护路径不应消耗本地名额");
    }

    @Test
    @DisplayName("Redis 不可用时降级放行，且本地名额耗尽后仍然 fail-open")
    void degradesToLocalSemaphoreAndFailsOpen() throws Exception {
        RedisQueueService service = newDegradedService(2);

        QueueVerdict first = service.acquire(PROTECTED_PATH, PROTECTED_METHOD, null);
        QueueVerdict second = service.acquire(PROTECTED_PATH, PROTECTED_METHOD, null);
        QueueVerdict third = service.acquire(PROTECTED_PATH, PROTECTED_METHOD, null);

        assertTrue(first.isPassed() && second.isPassed() && third.isPassed(),
                "降级路径必须全部放行：排队是流控手段，不能变成新的可用性单点");
        assertNotNull(first.getPermitToken(), "拿到本地名额时应带票，便于归还");
        assertNotNull(second.getPermitToken());
        assertNull(third.getPermitToken(), "本地名额耗尽时应 fail-open 放行且不带票");
        assertEquals(0, availablePermits(service));
    }

    @Test
    @DisplayName("本地名额重复归还不会造成许可虚增")
    void localReleaseIsIdempotent() throws Exception {
        RedisQueueService service = newDegradedService(2);

        QueueVerdict first = service.acquire(PROTECTED_PATH, PROTECTED_METHOD, null);
        service.acquire(PROTECTED_PATH, PROTECTED_METHOD, null);
        assertEquals(0, availablePermits(service), "两张票都发出后应无剩余名额");

        service.release(first);
        service.release(first);
        service.release(first);

        assertEquals(1, availablePermits(service),
                "重复归还同一张票只能恢复一个名额，否则并发上限会被无声放大");
    }

    @Test
    @DisplayName("归还 null / 无票放行结果时是安全的空操作")
    void releaseWithoutPermitIsNoop() throws Exception {
        RedisQueueService service = newDegradedService(2);

        service.release(null);
        service.release(QueueVerdict.PASS);
        service.release(QueueVerdict.respond(QueueJson.CODE_QUEUED, "{}"));

        assertEquals(2, availablePermits(service), "空操作不应改变名额数");
    }

    @Test
    @DisplayName("非法票据一律判为失效，不会被写进 Redis")
    void pollRejectsIllegalToken() {
        RedisQueueService service = newDegradedService(2);

        assertEquals(QueuePollResult.State.EXPIRED, service.poll(null).getState(),
                "null 票据");
        assertEquals(QueuePollResult.State.EXPIRED, service.poll("").getState(),
                "空串票据");
        assertEquals(QueuePollResult.State.EXPIRED, service.poll("abc").getState(),
                "过短票据");
        assertEquals(QueuePollResult.State.EXPIRED,
                service.poll("../../../etc/passwd").getState(),
                "含非法字符的票据");
        assertEquals(QueuePollResult.State.EXPIRED,
                service.poll(repeat("a", 65)).getState(),
                "超长票据");
    }

    @Test
    @DisplayName("合法票据在 Redis 不可用时降级为直接放行")
    void pollDegradesToReadyWhenRedisMissing() {
        RedisQueueService service = newDegradedService(2);

        QueuePollResult result = service.poll("1f0c3b2a-1234-4567-89ab-cdef01234567");

        assertEquals(QueuePollResult.State.READY, result.getState(),
                "Redis 挂了不能把客户端永远卡在轮询里");
    }

    /**
     * 生成重复字符串。项目基线为 JDK 8 语法兼容，不使用 {@code String#repeat}。
     *
     * @param unit  重复单元
     * @param times 次数
     * @return 拼接结果
     */
    private String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(unit);
        }
        return builder.toString();
    }
}
