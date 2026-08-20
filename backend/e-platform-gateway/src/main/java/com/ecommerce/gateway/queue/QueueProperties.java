package com.ecommerce.gateway.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 秒杀排队配置（配置前缀 {@code queue}）。
 *
 * <p>所有时间参数<b>对外单位一律是「秒」</b>，与 {@code .env.example} 中
 * {@code QUEUE_WAIT_TIMEOUT} / {@code QUEUE_PERMIT_TTL} 的语义保持一致；
 * 内部与 Redis 交互时才转成毫秒（Redis ZSet 的 score 用毫秒时间戳，秒级精度不够）。
 * 单位混用是这类组件最常见的线上事故来源，因此这里用 {@code xxxMs()} 方法名做显式区分，
 * <b>禁止在别处直接对字段做 {@code * 1000}</b>。
 *
 * <h3>为什么校验失败是「钳制 + WARN」而不是 fail-fast</h3>
 * {@code GatewaySecurityProperties} 对密钥缺失是启动即失败的 —— 因为签名密钥错了会导致
 * 全站 401，静默运行比崩溃更糟。但排队是<b>流控</b>而非安全边界：把 {@code permits}
 * 误配成 0 就让整个网关起不来，反而放大了故障面。所以这里取「钳到合法区间 + 大声记 WARN」。
 */
@Component
@ConfigurationProperties(prefix = "queue")
public class QueueProperties {

    private static final Logger log = LoggerFactory.getLogger(QueueProperties.class);

    /** 受保护路径规则的「方法:模式」分隔符。 */
    private static final char RULE_SEPARATOR = ':';

    /** 规则中代表「任意 HTTP 方法」的通配符。 */
    private static final String ANY_METHOD = "*";

    /** 每秒的毫秒数，避免代码里散落裸的 1000。 */
    private static final long MILLIS_PER_SECOND = 1000L;

    /**
     * ZSet 键自身的存活倍率。键 TTL = max(名额TTL, 等待超时) * 本倍率。
     *
     * <p>成员级过期已由 {@code ZREMRANGEBYSCORE} 兜底，键级 TTL 只是防止
     * 「流量彻底停止后两个 ZSet 永远残留在 Redis 里」。倍率取 2 是为了保证
     * 键绝不会在仍有有效成员时被整体抹掉（那会造成一次名额超发）。
     */
    private static final int KEY_TTL_MULTIPLIER = 2;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** 排队总开关。关闭后本模块不注册 QueueService Bean，由 NoopQueueService 兜底放行。 */
    private boolean enabled = true;

    /** 并发名额数，即同一时刻允许进入下单流程的请求数。 */
    private int permits = 50;

    /** 等待队列最大长度，超出直接 503。设为 0 表示「不排队，满了就拒」。 */
    private int maxLength = 500;

    /** 排队等待超时（秒）。超过该时长仍未被晋升的票据会被清理，客户端收到 408。 */
    private int waitTimeout = 60;

    /** 名额票据有效期（秒）。客户端拿到名额后崩溃不再发请求时，名额靠它自动回收。 */
    private int permitTtl = 30;

    /** 建议客户端的轮询间隔（秒），随 202 响应下发，客户端不必硬编码。 */
    private int pollInterval = 2;

    /**
     * 受保护路径，格式 {@code METHOD:antPattern}，{@code METHOD} 可用 {@code *} 通配。
     *
     * <p>刻意做成白名单而非「全站生效」：给每个请求都加一次 Redis 往返，
     * 排队机制自己就会变成全站延迟的主要来源。
     */
    private List<String> protectedPaths = new ArrayList<>(Arrays.asList(
            "POST:/order/create",
            "PUT:/product/*/deduct"));

    /** 解析后的规则表，启动期一次性编译，请求期只读。 */
    private volatile List<Rule> compiledRules = Collections.emptyList();

    /**
     * 启动期钳制非法值并编译路径规则。
     *
     * <p>放在 {@code @PostConstruct} 而不是 setter 里，是因为 Spring 绑定器会逐个调用 setter，
     * 在中间态做校验会误报。
     */
    @PostConstruct
    public void init() {
        this.permits = clamp("queue.permits", permits, 1, Integer.MAX_VALUE, 50);
        this.maxLength = clamp("queue.max-length", maxLength, 0, Integer.MAX_VALUE, 500);
        this.waitTimeout = clamp("queue.wait-timeout", waitTimeout, 1, Integer.MAX_VALUE, 60);
        this.permitTtl = clamp("queue.permit-ttl", permitTtl, 1, Integer.MAX_VALUE, 30);
        this.pollInterval = clamp("queue.poll-interval", pollInterval, 1, Integer.MAX_VALUE, 2);
        this.compiledRules = Collections.unmodifiableList(compileRules(protectedPaths));

        if (enabled) {
            log.info("秒杀排队已启用: permits={}, maxLength={}, waitTimeout={}s, permitTtl={}s, "
                            + "pollInterval={}s, protectedPaths={}",
                    permits, maxLength, waitTimeout, permitTtl, pollInterval, protectedPaths);
        } else {
            log.info("秒杀排队已关闭（queue.enabled=false），网关将由 NoopQueueService 全量放行");
        }
    }

    /**
     * 判断某次请求是否需要走排队守卫。
     *
     * @param path   已剥离 {@code /api} 前缀的路径，如 {@code /order/create}
     * @param method HTTP 方法，大小写不敏感
     * @return true 表示需要占用名额
     */
    public boolean isProtected(String path, String method) {
        List<Rule> rules = compiledRules;
        if (rules.isEmpty()) {
            return false;
        }
        String normalizedPath = normalizePath(path);
        String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
        for (Rule rule : rules) {
            if (rule.matches(normalizedPath, normalizedMethod)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return 名额票据有效期（毫秒）
     */
    public long permitTtlMs() {
        return permitTtl * MILLIS_PER_SECOND;
    }

    /**
     * @return 排队等待超时（毫秒）
     */
    public long waitTimeoutMs() {
        return waitTimeout * MILLIS_PER_SECOND;
    }

    /**
     * @return 两个 ZSet 键自身的存活时间（毫秒）
     */
    public long keyTtlMs() {
        return Math.max(permitTtlMs(), waitTimeoutMs()) * KEY_TTL_MULTIPLIER;
    }

    /**
     * 估算剩余等待秒数。
     *
     * <p>模型：每个轮询周期内，排在最前面的 {@code permits} 个等待者会随着名额释放被陆续晋升，
     * 因此队列大致以「每 {@code pollInterval} 秒前进 {@code permits} 位」的速度推进。
     * 这是<b>粗估</b>，只用于前端展示；结果被钳制在 {@code [pollInterval, waitTimeout]} 内，
     * 避免出现「预计等待 3000 秒」这种既吓人又超过票据寿命的数字。
     *
     * @param rankFromZero 0 起算的队列位次
     * @return 预计等待秒数，至少一个轮询间隔
     */
    public int estimateWaitSeconds(long rankFromZero) {
        long safeRank = Math.max(0L, rankFromZero);
        long cycles = (safeRank + permits) / permits;
        long seconds = cycles * pollInterval;
        return (int) Math.min(Math.max(seconds, pollInterval), waitTimeout);
    }

    /**
     * 把配置的字符串规则编译成可匹配对象，非法条目跳过并告警而非中断启动。
     *
     * @param raw 原始规则列表，可为 null
     * @return 编译结果，永不为 null
     */
    private List<Rule> compileRules(List<String> raw) {
        List<Rule> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String item : raw) {
            if (item == null || item.trim().isEmpty()) {
                continue;
            }
            String entry = item.trim();
            int sep = entry.indexOf(RULE_SEPARATOR);
            String method;
            String pattern;
            if (sep < 0) {
                // 缺少方法段时按「任意方法」处理，容忍最常见的手滑写法。
                method = ANY_METHOD;
                pattern = entry;
            } else {
                method = entry.substring(0, sep).trim().toUpperCase(Locale.ROOT);
                pattern = entry.substring(sep + 1).trim();
            }
            if (pattern.isEmpty() || !pattern.startsWith("/")) {
                log.warn("忽略非法的 queue.protected-paths 条目（路径必须以 / 开头）: {}", entry);
                continue;
            }
            if (method.isEmpty()) {
                method = ANY_METHOD;
            }
            result.add(new Rule(method, pattern));
        }
        return result;
    }

    /**
     * 去掉查询串与冗余尾斜杠，与 {@code RoutePermissionRegistry} 的归一化口径保持一致，
     * 避免同一条路径在两处判定结果不同。
     *
     * @param path 原始路径
     * @return 归一化路径
     */
    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String result = path;
        int queryIndex = result.indexOf('?');
        if (queryIndex >= 0) {
            result = result.substring(0, queryIndex);
        }
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 把越界的配置值钳回合法区间。
     *
     * @param name         配置键名，仅用于日志
     * @param value        实际值
     * @param min          允许的最小值
     * @param max          允许的最大值
     * @param fallback     越界时采用的值
     * @return 合法值
     */
    private int clamp(String name, int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            log.warn("配置 {} = {} 越界（允许 [{}, {}]），已回退为 {}", name, value, min, max, fallback);
            return fallback;
        }
        return value;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPermits() {
        return permits;
    }

    public void setPermits(int permits) {
        this.permits = permits;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public int getWaitTimeout() {
        return waitTimeout;
    }

    public void setWaitTimeout(int waitTimeout) {
        this.waitTimeout = waitTimeout;
    }

    public int getPermitTtl() {
        return permitTtl;
    }

    public void setPermitTtl(int permitTtl) {
        this.permitTtl = permitTtl;
    }

    public int getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(int pollInterval) {
        this.pollInterval = pollInterval;
    }

    public List<String> getProtectedPaths() {
        return protectedPaths;
    }

    public void setProtectedPaths(List<String> protectedPaths) {
        this.protectedPaths = protectedPaths;
    }

    /** 单条受保护路径规则。 */
    private static final class Rule {

        private final String method;
        private final String pattern;

        private Rule(String method, String pattern) {
            this.method = method;
            this.pattern = pattern;
        }

        /**
         * @param path   归一化路径
         * @param method 大写 HTTP 方法
         * @return 方法与路径是否同时命中
         */
        boolean matches(String path, String method) {
            if (!ANY_METHOD.equals(this.method) && !this.method.equals(method)) {
                return false;
            }
            return PATH_MATCHER.match(this.pattern, path);
        }

        @Override
        public String toString() {
            return method + RULE_SEPARATOR + pattern;
        }
    }
}
