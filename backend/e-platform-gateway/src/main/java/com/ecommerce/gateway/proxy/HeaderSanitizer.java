package com.ecommerce.gateway.proxy;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 下游请求头清洗器 —— 修复 <b>C4：Gateway Header 伪造</b>。
 *
 * <h3>漏洞成因</h3>
 * 旧实现是「全量透传 → 再用 Token 覆盖」：
 * <pre>
 *   for (header : request.getHeaderNames()) proxyHeaders.set(header, ...);  // X-User-Id 被原样带上
 *   extractUserFromToken(request, proxyHeaders);                            // 只有 Token 里有 userId 才覆盖
 * </pre>
 * 于是只要满足下面任一条件，客户端伪造的 {@code X-User-Id} 就能穿透到下游：
 * <ul>
 *   <li>请求走的是公开路径（根本不带 Token）；</li>
 *   <li>Token 能验签但缺少 {@code userId} claim（覆盖分支不执行）。</li>
 * </ul>
 *
 * <h3>修复策略：先剥离，后写入，且剥离基于前缀而非枚举</h3>
 * <p>枚举式黑名单（只挡 {@code X-User-Id/X-User-Type/X-User-Roles/X-Gateway-Sign} 四个头）
 * 在任何人新增第五个内部头时都会重新引入漏洞。<b>前缀规则是"默认拒绝"，一劳永逸。</b>
 *
 * <p>{@code x-internal-} 也必须剥离，否则外部可伪造 {@code X-Internal-Token}
 * 直接调用 product 的库存接口；order → product 是服务间直连、不经网关，不受此规则影响。
 *
 * <p>{@code Authorization} <b>保留透传</b>：mobile BFF 的 {@code AuthInterceptor}
 * 仍需自行解析 JWT，剥掉会直接打断移动端链路。它是客户端凭证而非内部信任凭证，
 * 下游对它的信任等级本来就低于 {@code X-Gateway-Sign}。
 */
@Component
public class HeaderSanitizer {

    /**
     * 逐跳（hop-by-hop）头：只在单跳连接内有意义，转发时必须由 HTTP 客户端重新计算。
     * 透传会导致 {@code Content-Length} 与实际 body 不符、连接复用错乱等诡异问题。
     */
    private static final Set<String> HOP_BY_HOP = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "host",
            "content-length",
            "connection",
            "keep-alive",
            "transfer-encoding",
            "upgrade",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer")));

    /**
     * 前缀黑名单：一切内部身份 / 信任凭证头，客户端永远无权携带。
     * 命中即丢弃，<b>不设任何例外</b>。
     *
     * <p>{@code x-queue-}（承载 {@code X-Queue-Token} 排队票据）也在列。它与前三个不同 ——
     * 它<b>是</b>客户端合法携带的头，网关会在管线第 4 步读取它来判定名额归属。
     * 但读取发生在<b>本清洗器之前</b>，下游服务对它没有任何用途，
     * 因此按「最小暴露面」原则在转发时剥离：票据是网关内部的并发控制凭证，
     * 多传一跳只会多一处被日志记录、被下游误信任的机会。
     *
     * <p><b>顺序依赖</b>：{@code ProxyService} 的管线顺序是「4. 排队守卫 → 5. 头清洗」。
     * 若将来有人把清洗提前到排队之前，排队将永远读不到票据、退化为每次都重新排队。
     */
    private static final List<String> BLOCKED_PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "x-user-",
            "x-gateway-",
            "x-internal-",
            "x-queue-"));

    /**
     * 判断某个客户端请求头是否必须被丢弃。
     *
     * @param name 请求头名，大小写不敏感
     * @return true 表示禁止透传
     */
    public boolean isBlocked(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (HOP_BY_HOP.contains(lower)) {
            return true;
        }
        for (String prefix : BLOCKED_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 基于原始请求构造<b>干净</b>的下游请求头。
     *
     * <p>返回结果中<b>一定不含</b>任何 {@code X-User-*} / {@code X-Gateway-*} /
     * {@code X-Internal-*} 头，随后由 {@code GatewaySigner} 写入网关认定的可信身份。
     *
     * <p>多值头（如多个 {@code Accept}）会被完整保留，避免丢失 {@code Accept-Encoding} 协商信息。
     *
     * @param request 原始客户端请求
     * @return 已清洗的请求头集合，永不为 null
     */
    public HttpHeaders sanitize(HttpServletRequest request) {
        HttpHeaders clean = new HttpHeaders();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return clean;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (isBlocked(name)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values != null && values.hasMoreElements()) {
                String value = values.nextElement();
                if (value != null) {
                    clean.add(name, value);
                }
            }
        }
        return clean;
    }
}
