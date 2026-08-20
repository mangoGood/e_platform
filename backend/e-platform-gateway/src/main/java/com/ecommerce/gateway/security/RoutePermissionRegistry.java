package com.ecommerce.gateway.security;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 路由权限注册表：「路径 Pattern + HTTP 方法 → 访问策略」的有序映射表。
 *
 * <p>替代原先散落的 {@code isPublicPath} / {@code isPublicReadPath} 判断。
 *
 * <h3>两条铁律</h3>
 * <ol>
 *   <li><b>顺序敏感</b>：从上往下第一条命中即生效，因此更具体的规则必须写在更宽泛的规则之前。</li>
 *   <li><b>默认拒绝</b>：兜底规则为 {@code AUTHENTICATED}，任何新接口即使忘记登记也不会裸奔。</li>
 * </ol>
 *
 * <p>网关只做「有没有这张门票」的粗判；「这张票是不是你自己的资源」（归属校验）
 * 一律下沉到服务层 —— 网关拿不到 {@code product.seller_id} / {@code order.user_id}，
 * 强行做归属校验会引入网关→DB 依赖。
 *
 * <p><b>维护提醒</b>：新增权限码必须同时改三处 ——
 * {@code migration-v2.sql} 的 seed、本注册表、{@link StaticRolePermissions} 的兜底表。
 */
@Component
public class RoutePermissionRegistry {

    /** 通配任意 HTTP 方法。 */
    private static final Set<String> ANY_METHOD = Collections.emptySet();

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** 有序规则表，构造时一次性初始化后不再变更。 */
    private final List<Rule> rules;

    public RoutePermissionRegistry() {
        this.rules = Collections.unmodifiableList(buildRules());
    }

    /**
     * 依据请求路径与方法查表。
     *
     * @param path   已剥离 {@code /api} 前缀的路径，如 {@code /product/add}
     * @param method HTTP 方法，大小写不敏感
     * @return 命中的访问策略；无命中时返回兜底的 {@link AccessDecision#AUTHENTICATED}
     */
    public AccessDecision decide(String path, String method) {
        String normalizedPath = normalizePath(path);
        String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);

        for (Rule rule : rules) {
            if (rule.matches(normalizedPath, normalizedMethod)) {
                return rule.decision;
            }
        }
        // 理论上不可达（最后一条规则是 /** 兜底），保留以防规则表被误改。
        return AccessDecision.AUTHENTICATED;
    }

    /**
     * 去除查询串与重复斜杠，保证 AntPathMatcher 匹配结果稳定。
     *
     * @param path 原始路径
     * @return 规范化路径，输入为空时返回 {@code "/"}
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
        // 去除尾部斜杠（根路径除外），避免 "/order/cart/" 漏匹配 "/order/cart"
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 构造规则表。顺序即优先级，切勿随意调整。
     *
     * @return 有序规则列表
     */
    private List<Rule> buildRules() {
        List<Rule> list = new ArrayList<>();

        // ---- 1. 内部专用接口：外部一律 403（最高优先级，先于一切认证判断）----
        list.add(Rule.of(AccessDecision.INTERNAL_DENY, ANY_METHOD,
                "/product/*/deduct", "/product/*/restore"));
        list.add(Rule.of(AccessDecision.INTERNAL_DENY, ANY_METHOD,
                "/**/internal/**", "/*/internal", "/internal/**"));

        // ---- 2~4. 认证域 ----
        list.add(Rule.of(AccessDecision.PUBLIC, methods("POST"),
                "/user/login", "/user/register", "/mobile/auth/login", "/mobile/auth/register"));
        list.add(Rule.of(AccessDecision.PUBLIC, methods("POST"), "/user/refresh"));
        list.add(Rule.of(AccessDecision.AUTHENTICATED, methods("POST"), "/user/logout"));

        // ---- 5~8. 公开读 ----
        list.add(Rule.of(AccessDecision.PUBLIC, methods("GET"),
                "/product/list", "/product/batch", "/product/seller/*", "/product/*"));
        list.add(Rule.of(AccessDecision.PUBLIC, methods("GET"), "/category/**"));
        list.add(Rule.of(AccessDecision.PUBLIC, methods("GET"),
                "/comment/product/**", "/comment/*/replies", "/review/**"));
        list.add(Rule.of(AccessDecision.PUBLIC, methods("GET"),
                "/mobile/home", "/mobile/home/**", "/mobile/products", "/mobile/products/**"));

        // ---- 9. 排队接口（T03 实现，规则先行登记）----
        list.add(Rule.of(AccessDecision.AUTHENTICATED, ANY_METHOD, "/queue", "/queue/**"));

        // ---- 10~12. 商品写操作 ----
        list.add(Rule.of(AccessDecision.permission("product:write"), methods("POST"), "/product/add"));
        list.add(Rule.of(AccessDecision.permission("product:write"), methods("PUT"), "/product/*"));
        list.add(Rule.of(AccessDecision.permission("product:delete"), methods("DELETE"), "/product/*"));

        // ---- 13~16. 订单域 ----
        list.add(Rule.of(AccessDecision.permission("cart:manage"), ANY_METHOD,
                "/order/cart", "/order/cart/**"));
        list.add(Rule.of(AccessDecision.permission("order:create"), methods("POST"), "/order/create"));
        list.add(Rule.of(AccessDecision.permission("order:manage"), methods("POST"), "/order/deliver/**"));
        list.add(Rule.of(AccessDecision.permission("order:read"), ANY_METHOD,
                "/order", "/order/**", "/address", "/address/**"));

        // ---- 17~20. 评论域（T03 实现，规则先行登记）----
        list.add(Rule.of(AccessDecision.permission("comment:create"), methods("POST"), "/comment"));
        list.add(Rule.of(AccessDecision.permission("comment:reply"), methods("POST"), "/comment/reply"));
        list.add(Rule.of(AccessDecision.permission("comment:ask"), methods("POST"), "/comment/ask"));
        list.add(Rule.of(AccessDecision.AUTHENTICATED, methods("PUT", "DELETE"), "/comment/**"));

        // ---- 21. 后台 ----
        list.add(Rule.of(AccessDecision.permission("admin:access"), ANY_METHOD, "/admin", "/admin/**"));

        // ---- 22. 移动端 BFF 其余接口 ----
        list.add(Rule.of(AccessDecision.AUTHENTICATED, ANY_METHOD, "/mobile/**"));

        // ---- 兜底：默认拒绝（未登录即 401）----
        list.add(Rule.of(AccessDecision.AUTHENTICATED, ANY_METHOD, "/**"));

        return list;
    }

    /**
     * @param values HTTP 方法名
     * @return 大写化的方法集合
     */
    private static Set<String> methods(String... values) {
        Set<String> set = new LinkedHashSet<>();
        for (String value : values) {
            set.add(value.toUpperCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(set);
    }

    /** 单条路由规则。 */
    private static final class Rule {

        private final List<String> patterns;
        private final Set<String> httpMethods;
        private final AccessDecision decision;

        private Rule(AccessDecision decision, Set<String> httpMethods, List<String> patterns) {
            this.decision = decision;
            this.httpMethods = httpMethods;
            this.patterns = patterns;
        }

        /**
         * @param decision    命中后生效的策略
         * @param httpMethods 适用方法集合，空集表示任意方法
         * @param patterns    Ant 风格路径 Pattern
         * @return 规则实例
         */
        static Rule of(AccessDecision decision, Set<String> httpMethods, String... patterns) {
            return new Rule(decision, httpMethods,
                    Collections.unmodifiableList(Arrays.asList(patterns)));
        }

        /**
         * @param path   规范化后的路径
         * @param method 大写化的 HTTP 方法
         * @return 路径与方法是否同时命中
         */
        boolean matches(String path, String method) {
            if (!httpMethods.isEmpty() && !httpMethods.contains(method)) {
                return false;
            }
            for (String pattern : patterns) {
                if (PATH_MATCHER.match(pattern, path)) {
                    return true;
                }
            }
            return false;
        }
    }
}
