package com.ecommerce.gateway.queue;

import com.ecommerce.gateway.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 基于 Redis ZSet + Lua 的秒杀排队守卫。
 *
 * <h3>为什么不能阻塞等待</h3>
 * 网关是<b>同步 Servlet 模型</b>（手写 Spring MVC + RestTemplate 代理，不是 WebFlux）。
 * 50 个名额配 500 长的队列，如果让线程 {@code await()} 等名额，Tomcat 的 200 个工作线程
 * 会在几百毫秒内被排队请求全部占满 —— 连「查询商品列表」这种和秒杀无关的请求都进不来，
 * <b>排队机制自己变成了故障源</b>。所以这里采用「立即应答 + 客户端轮询 + 票据回传」：
 * <ol>
 *   <li>无票请求命中受保护路径 → 有空位当场发票放行；没空位则入队并<b>立刻</b>返回 202；</li>
 *   <li>客户端拿 202 里的 {@code queueToken} 轮询 {@code GET /api/queue/status}；</li>
 *   <li>轮到了 → 轮询接口把票据从等待队列<b>晋升</b>进活跃名额集合；</li>
 *   <li>客户端带 {@code X-Queue-Token} 重发原请求 → 本类识别到票据已激活，直接放行；</li>
 *   <li>转发结束，管线的 {@code finally} 调用 {@link #release} 归还（ZREM，幂等）。</li>
 * </ol>
 * 整条链路上网关线程<b>零阻塞</b>，等待成本完全由客户端承担。
 *
 * <h3>为什么票据要从 ThreadLocal 里取而不是走方法参数</h3>
 * {@link QueueService#acquire} 的签名由 T02 固定为 {@code (path, method, principal)}，
 * 拿不到 {@code HttpServletRequest}。改签名会牵动 {@code ProxyService} 的安全管线 ——
 * 那条管线的顺序是不可随意变更的契约。因此这里用 {@code RequestContextHolder} 读取
 * {@code X-Queue-Token}：Spring MVC 的 {@code DispatcherServlet} 在
 * {@code FrameworkServlet#processRequest} 中必定填充该 ThreadLocal，网关又没有异步派发，
 * 取值是可靠的。取不到时按「无票」处理，只会多排一次队，不会出错。
 *
 * <h3>降级：Redis 挂了必须放行，不能拒绝</h3>
 * 排队是<b>流控手段</b>而非安全边界。Redis 故障导致全站下不了单，比不排队严重得多。
 * 所以 Redis 异常时切到本地 {@link Semaphore} 非阻塞兜底，连本地名额都拿不到时
 * <b>仍然放行</b>（fail-open），只记降频 WARN。
 *
 * @see QueueVerdict
 * @see QueueProperties
 */
@Component
@ConditionalOnProperty(prefix = "queue", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisQueueService implements QueueService {

    private static final Logger log = LoggerFactory.getLogger(RedisQueueService.class);

    /** 客户端回传票据用的请求头。与 {@code .env.example} 中的约定一致，前端/Android 都按此对接。 */
    public static final String QUEUE_TOKEN_HEADER = "X-Queue-Token";

    /** 活跃名额 ZSet：member=票据，score=名额过期时间戳(ms)。 */
    public static final String ACTIVE_KEY = "queue:permits:active";

    /** 等待队列 ZSet：member=票据，score=入队时间戳(ms)。 */
    public static final String WAITING_KEY = "queue:waiting";

    /** 本地降级名额的票据前缀，用于在 {@link #release} 中区分归还目标。 */
    private static final String LOCAL_PERMIT_PREFIX = "local:";

    /** 降级 WARN 的最小间隔（毫秒）。Redis 挂掉时每个请求都打日志会瞬间刷爆磁盘。 */
    private static final long DEGRADE_LOG_INTERVAL_MS = 5000L;

    /** 票据最大长度。客户端可控字段必须限长，否则可以往 Redis 里塞超大 member。 */
    private static final int MAX_TOKEN_LENGTH = 64;

    /** 票据最小长度，挡掉明显的探测性短串。 */
    private static final int MIN_TOKEN_LENGTH = 8;

    /** 票据白名单字符集：只允许 UUID 可能出现的十六进制字符与连字符。 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[0-9a-fA-F-]+$");

    /** Lua 返回码：客户端带票重发，已持有名额。 */
    private static final long ACQUIRE_PASS_WITH_CLAIM = 1L;

    /** Lua 返回码：当场发放了一张新票。 */
    private static final long ACQUIRE_PASS_WITH_FRESH = 2L;

    /** Lua 返回码：已入队等待。 */
    private static final long ACQUIRE_ENQUEUED = 0L;

    /** Lua 返回码：等待队列已满。 */
    private static final long ACQUIRE_QUEUE_FULL = -1L;

    /** Lua 返回码：轮询已就绪。 */
    private static final long POLL_READY = 1L;

    /** Lua 返回码：轮询仍在等待。 */
    private static final long POLL_WAITING = 0L;

    /** Lua 返回码：票据失效。 */
    private static final long POLL_EXPIRED = -2L;

    /** Lua 返回数组中「状态码」的下标。 */
    private static final int IDX_CODE = 0;

    /** Lua 返回数组中「位次」的下标。 */
    private static final int IDX_RANK = 1;

    private final QueueProperties properties;

    /** Redis 可能整体不可用，沿用 {@code TokenResolver} 的 ObjectProvider 惯例延迟获取。 */
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    private final RedisScript<List> acquireScript;
    private final RedisScript<List> pollScript;
    private final RedisScript<Long> releaseScript;

    /** Redis 不可用时的本地兜底名额。非阻塞 {@code tryAcquire}，绝不让线程等待。 */
    private final Semaphore localFallback;

    /**
     * 已发放但尚未归还的本地票据。
     *
     * <p>存在的唯一理由是让本地路径的 {@link #release} 也<b>幂等</b>：
     * {@code Semaphore#release()} 会无脑增加许可数，重复调用会把名额越放越多，
     * 直接违背 {@link QueueService} 接口「重复归还不得导致名额虚增」的约定。
     * 集合规模上界就是 permits，内存开销可忽略。
     */
    private final Set<String> localPermits = ConcurrentHashMap.newKeySet();

    /** 上次打印降级 WARN 的时间戳，用于日志降频。 */
    private final AtomicLong lastDegradeLogAt = new AtomicLong(0L);

    public RedisQueueService(QueueProperties properties,
                             ObjectProvider<StringRedisTemplate> redisProvider) {
        this.properties = properties;
        this.redisProvider = redisProvider;
        this.localFallback = new Semaphore(properties.getPermits());
        this.acquireScript = loadListScript("lua/queue_acquire.lua");
        this.pollScript = loadListScript("lua/queue_poll.lua");
        this.releaseScript = loadLongScript("lua/queue_release.lua");
    }

    @Override
    public QueueVerdict acquire(String path, String method, AuthPrincipal principal) {
        // 整个方法体裹在 try 里：接口契约要求 acquire 永不抛异常。
        // 任何未预料的失败都必须退化成「放行」，而不是把 500 抛给客户端。
        try {
            if (!properties.isProtected(path, method)) {
                // 非受保护路径直接返回单例，不做任何 Redis 往返。
                return QueueVerdict.PASS;
            }

            String claimToken = readClaimToken();
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return degradeToLocal("Redis 未装配，排队降级为本地信号量", null);
            }

            String freshToken = newToken();
            long now = System.currentTimeMillis();

            List<?> raw = redis.execute(acquireScript,
                    Arrays.asList(ACTIVE_KEY, WAITING_KEY),
                    Long.toString(now),
                    claimToken == null ? "" : claimToken,
                    Integer.toString(properties.getPermits()),
                    Integer.toString(properties.getMaxLength()),
                    Long.toString(properties.permitTtlMs()),
                    Long.toString(properties.waitTimeoutMs()),
                    freshToken,
                    Long.toString(properties.keyTtlMs()));

            long code = readAt(raw, IDX_CODE, ACQUIRE_QUEUE_FULL);
            long rank = readAt(raw, IDX_RANK, 0L);

            if (code == ACQUIRE_PASS_WITH_CLAIM) {
                // 客户端带票重发：归还时要用客户端那张票，不是新生成的。
                return QueueVerdict.pass(claimToken);
            }
            if (code == ACQUIRE_PASS_WITH_FRESH) {
                return QueueVerdict.pass(freshToken);
            }
            if (code == ACQUIRE_ENQUEUED) {
                int position = (int) Math.min(Integer.MAX_VALUE, rank + 1);
                int estimate = properties.estimateWaitSeconds(rank);
                if (log.isDebugEnabled()) {
                    log.debug("请求入队: {} {}, userId={}, position={}", method, path,
                            principal == null ? 0L : principal.getUserId(), position);
                }
                return QueueVerdict.respond(QueueJson.CODE_QUEUED,
                        QueueJson.queuedBody(freshToken, position, estimate, properties.getPollInterval()));
            }
            // ACQUIRE_QUEUE_FULL 及一切未知返回码：按「队列已满」处理。
            // 未知码走拒绝而不是放行，是因为它只可能来自脚本被改坏，
            // 此时放行等于排队完全失效，而 503 至少是可观测的显性故障。
            log.warn("排队队列已满或脚本返回未知码: code={}, {} {}", code, method, path);
            return QueueVerdict.respond(QueueJson.CODE_QUEUE_FULL, QueueJson.queueFullBody());

        } catch (Exception e) {
            return degradeToLocal("排队申请失败，降级为本地信号量", e);
        }
    }

    @Override
    public void release(QueueVerdict verdict) {
        try {
            if (verdict == null) {
                return;
            }
            String token = verdict.getPermitToken();
            if (token == null || token.isEmpty()) {
                // QueueVerdict.PASS 没有票据，本来就没占名额。
                return;
            }
            if (token.startsWith(LOCAL_PERMIT_PREFIX)) {
                // remove 返回 true 才归还，保证重复调用不会把许可数越放越大。
                if (localPermits.remove(token)) {
                    localFallback.release();
                }
                return;
            }
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return;
            }
            redis.execute(releaseScript, Collections.singletonList(ACTIVE_KEY), token);
        } catch (Exception e) {
            // 归还失败不影响本次请求结果，且名额有 TTL 兜底会自动过期，不会永久泄漏。
            warnThrottled("归还排队名额失败，名额将由 TTL 自动回收", e);
        }
    }

    /**
     * 轮询排队进度，必要时把票据从等待队列晋升进活跃名额集合。
     *
     * <p>供 {@code QueueController} 调用。与 {@link #acquire} 一样<b>永不抛异常</b>。
     *
     * @param rawToken 客户端传来的票据，可为 null / 非法
     * @return 轮询结果，永不为 null
     */
    public QueuePollResult poll(String rawToken) {
        String token = sanitizeToken(rawToken);
        if (token == null) {
            return QueuePollResult.expired();
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            // 与 acquire 的降级口径保持一致：Redis 没了就别拦人。
            // 客户端随后带票重发，acquire 会走本地信号量兜底。
            warnThrottled("Redis 未装配，排队状态查询降级为直接放行", null);
            return QueuePollResult.ready(token);
        }
        try {
            List<?> raw = redis.execute(pollScript,
                    Arrays.asList(ACTIVE_KEY, WAITING_KEY),
                    Long.toString(System.currentTimeMillis()),
                    token,
                    Integer.toString(properties.getPermits()),
                    Long.toString(properties.permitTtlMs()),
                    Long.toString(properties.waitTimeoutMs()),
                    Long.toString(properties.keyTtlMs()));

            long code = readAt(raw, IDX_CODE, POLL_EXPIRED);
            long rank = readAt(raw, IDX_RANK, 0L);

            if (code == POLL_READY) {
                return QueuePollResult.ready(token);
            }
            if (code == POLL_WAITING) {
                int position = (int) Math.min(Integer.MAX_VALUE, rank + 1);
                return QueuePollResult.waiting(token, position,
                        properties.estimateWaitSeconds(rank), properties.getPollInterval());
            }
            // POLL_EXPIRED 及未知码统一按失效处理，客户端的动作都是「重新提交原请求」。
            return QueuePollResult.expired();
        } catch (Exception e) {
            warnThrottled("排队状态查询失败，降级为直接放行", e);
            return QueuePollResult.ready(token);
        }
    }

    /**
     * 从当前请求上下文读取并校验客户端回传的票据。
     *
     * @return 合法票据；无票或票据非法时返回 null（按无票处理，最多多排一次队）
     */
    private String readClaimToken() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes)) {
            return null;
        }
        HttpServletRequest request = ((ServletRequestAttributes) attributes).getRequest();
        if (request == null) {
            return null;
        }
        return sanitizeToken(request.getHeader(QUEUE_TOKEN_HEADER));
    }

    /**
     * 校验客户端可控的票据字符串。
     *
     * <p>{@code X-Queue-Token} 和 {@code ?token=} 都是<b>客户端完全可控</b>的输入，会被当作
     * Redis ZSet 的 member 使用。不限长会让攻击者往 Redis 里塞 MB 级 member 打爆内存；
     * 不限字符集则可能把控制字符带进 JSON 响应体。这里用白名单正则一次性挡掉。
     *
     * <p><b>注意</b>：校验通过<b>不代表票据有效</b>，只代表格式安全 ——
     * 是否真的持有名额由 Lua 脚本里的 {@code ZSCORE} 判定。
     *
     * @param raw 原始字符串，可为 null
     * @return 合法票据；非法时返回 null
     */
    private String sanitizeToken(String raw) {
        if (raw == null) {
            return null;
        }
        String token = raw.trim();
        if (token.length() < MIN_TOKEN_LENGTH || token.length() > MAX_TOKEN_LENGTH) {
            return null;
        }
        if (!TOKEN_PATTERN.matcher(token).matches()) {
            return null;
        }
        return token;
    }

    /**
     * 生成服务端票据。
     *
     * <p>刻意<b>不复用客户端传来的未激活票据</b>：如果客户端能自选 member，
     * 就能故意与别人的票据撞名、或用固定值反复占位。票据一律服务端生成，
     * 客户端传来的值只用于「查询是否已激活」，不会被写入 Redis。
     *
     * @return 不可猜测的票据
     */
    private String newToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * 切换到本地信号量兜底。
     *
     * @param reason 降级原因，用于日志
     * @param cause  触发降级的异常，可为 null
     * @return 放行结果（拿到本地名额时带票，拿不到时也放行）
     */
    private QueueVerdict degradeToLocal(String reason, Exception cause) {
        warnThrottled(reason, cause);
        if (localFallback.tryAcquire()) {
            String token = LOCAL_PERMIT_PREFIX + UUID.randomUUID();
            localPermits.add(token);
            return QueueVerdict.pass(token);
        }
        // 本地名额也满了：仍然放行。排队是流控，不能变成新的可用性单点 ——
        // Redis 挂掉导致全站下不了单，比暂时不限流严重得多。
        return QueueVerdict.PASS;
    }

    /**
     * 降频 WARN：同一时间窗内只打一条。
     *
     * <p>Redis 挂掉时每个请求都会走到这里，不降频的话日志量等于 QPS，
     * 几十秒就能把磁盘写满，反而妨碍故障排查。
     *
     * @param message 日志正文
     * @param cause   异常，可为 null
     */
    private void warnThrottled(String message, Exception cause) {
        long now = System.currentTimeMillis();
        long last = lastDegradeLogAt.get();
        if (now - last < DEGRADE_LOG_INTERVAL_MS || !lastDegradeLogAt.compareAndSet(last, now)) {
            return;
        }
        if (cause == null) {
            log.warn("[排队降级] {}（{}秒内相同告警只记录一次）", message, DEGRADE_LOG_INTERVAL_MS / 1000);
        } else {
            // 只记异常摘要不打完整堆栈：降级路径是可预期分支而非缺陷，
            // 打堆栈会在 Redis 抖动时刷屏；真要定位可临时把 gateway 日志级别调到 DEBUG。
            log.warn("[排队降级] {}: {}（{}秒内相同告警只记录一次）",
                    message, cause.toString(), DEGRADE_LOG_INTERVAL_MS / 1000);
            log.debug("[排队降级] 详细堆栈", cause);
        }
    }

    /**
     * 从 Lua 返回数组中安全取出一个整数。
     *
     * <p>脚本保证返回两个整数元素，这里仍做完整防御：脚本被改坏时不能让网关抛 500。
     *
     * @param raw          Lua 返回值
     * @param index        下标
     * @param defaultValue 缺失或类型不符时的回退值
     * @return 整数值
     */
    private long readAt(List<?> raw, int index, long defaultValue) {
        if (raw == null || raw.size() <= index) {
            return defaultValue;
        }
        Object value = raw.get(index);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 加载返回数组的 Lua 脚本。
     *
     * @param location classpath 相对路径
     * @return 可执行脚本
     */
    @SuppressWarnings("rawtypes")
    private RedisScript<List> loadListScript(String location) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(location)));
        script.setResultType(List.class);
        // 立刻触发一次读取 + SHA 计算：脚本文件缺失时启动即失败，
        // 好过等到秒杀高峰第一个请求进来才发现打包漏了 resources/lua。
        script.getSha1();
        return script;
    }

    /**
     * 加载返回整数的 Lua 脚本。
     *
     * @param location classpath 相对路径
     * @return 可执行脚本
     */
    private RedisScript<Long> loadLongScript(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(location)));
        script.setResultType(Long.class);
        script.getSha1();
        return script;
    }
}
