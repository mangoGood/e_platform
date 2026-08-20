package com.ecommerce.common.security;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.util.HmacUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 网关签名校验拦截器 —— 微服务侧信任链的唯一入口（修复 C5：微服务裸奔）。
 *
 * <p>在此拦截器生效之前，任何人只要能访问 {@code localhost:8085~8089}，
 * 携带一个自己编造的 {@code X-User-Id} 就能冒充任意用户。启用之后，
 * 身份头必须附带由网关用共享密钥签发的 {@code X-Gateway-Sign} 才被接受。
 *
 * <h3>放行规则（按顺序判定）</h3>
 * <ol>
 *   <li>{@code gateway.sign.enabled=false} → 全放行（仅供本地调试）</li>
 *   <li>白名单路径：{@code /actuator/**}、{@code /error} → 放行</li>
 *   <li>{@code X-Internal-Token} 校验通过 → 放行（order→product 的 Feign 直连不经网关，
 *       由内部令牌独立保护；该头会被网关的 HeaderSanitizer 从外部请求中剥离）</li>
 *   <li>其余：必须通过 HMAC 验签 + 时间窗校验</li>
 * </ol>
 */
public class GatewaySignatureInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GatewaySignatureInterceptor.class);

    /** 网关下发的签名头。 */
    public static final String HEADER_SIGN = "X-Gateway-Sign";

    /** 网关下发的时间戳头（epoch 毫秒）。 */
    public static final String HEADER_TS = "X-Gateway-Ts";

    /** 网关下发的用户 ID 头。 */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 网关下发的用户类型头。 */
    public static final String HEADER_USER_TYPE = "X-User-Type";

    /** 网关下发的角色列表头（字典序、逗号连接、无空格）。 */
    public static final String HEADER_USER_ROLES = "X-User-Roles";

    /** 服务间直连令牌头。 */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    /** 匿名占位值。 */
    private static final String ANONYMOUS_VALUE = "0";

    /** 免验签路径。 */
    private static final List<String> WHITELIST_PATTERNS = Collections.unmodifiableList(Arrays.asList(
            "/actuator/**",
            "/error"));

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final GatewaySignProperties properties;

    /** 服务间直连令牌，与 {@code internal.token} 配置一致；未配置时为空串（等价于禁用该白名单）。 */
    @Value("${internal.token:}")
    private String internalToken = "";

    /**
     * @param properties 签名配置，由自动装配注入
     */
    public GatewaySignatureInterceptor(GatewaySignProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // OPTIONS 预检不携带业务身份，直接放行，交由 CORS 处理器响应。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (!properties.isEnabled()) {
            return true;
        }

        String uri = request.getRequestURI();
        if (isWhitelisted(uri)) {
            return true;
        }
        if (isTrustedInternalCall(request)) {
            // 内部直连链路（order -> product 的 deduct/restore）不经网关，无签名可用。
            return true;
        }

        String sign = request.getHeader(HEADER_SIGN);
        String ts = request.getHeader(HEADER_TS);
        if (sign == null || sign.isEmpty() || ts == null || ts.isEmpty()) {
            log.warn("拒绝无签名请求: uri={}, remote={}", uri, request.getRemoteAddr());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "非法请求来源");
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            log.warn("拒绝时间戳格式非法的请求: uri={}, ts={}", uri, ts);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "非法请求来源");
        }
        if (Math.abs(System.currentTimeMillis() - timestamp) > properties.getToleranceMs()) {
            log.warn("拒绝超出时间窗的请求: uri={}, ts={}", uri, timestamp);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请求已过期");
        }

        String userId = HmacUtil.nvl(request.getHeader(HEADER_USER_ID), ANONYMOUS_VALUE);
        String userType = HmacUtil.nvl(request.getHeader(HEADER_USER_TYPE), ANONYMOUS_VALUE);
        String roles = HmacUtil.nvl(request.getHeader(HEADER_USER_ROLES), "");

        String raw = HmacUtil.buildSignRaw(userId, userType, roles, ts.trim());
        String expected = HmacUtil.sign(properties.getSecret(), raw);

        // 常量时间比较，禁止 String.equals（存在时序侧信道）。
        if (!HmacUtil.constantTimeEquals(expected, sign)) {
            log.warn("拒绝签名不匹配的请求: uri={}, remote={}", uri, request.getRemoteAddr());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "签名校验失败");
        }

        GatewayUserContext.set(parseLong(userId), parseInt(userType), splitRoles(roles));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        GatewayUserContext.clear();
    }

    /**
     * @param uri 请求 URI
     * @return 是否命中免验签白名单
     */
    private boolean isWhitelisted(String uri) {
        for (String pattern : WHITELIST_PATTERNS) {
            if (PATH_MATCHER.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定是否为携带合法内部令牌的服务间直连调用。
     *
     * @param request 当前请求
     * @return 令牌已配置且常量时间比较通过时返回 true
     */
    private boolean isTrustedInternalCall(HttpServletRequest request) {
        if (internalToken == null || internalToken.isEmpty()) {
            return false;
        }
        String provided = request.getHeader(HEADER_INTERNAL_TOKEN);
        return HmacUtil.constantTimeEquals(internalToken, provided);
    }

    /**
     * @param roles 逗号连接的角色串
     * @return 角色列表，空串返回空列表
     */
    private List<String> splitRoles(String roles) {
        if (roles == null || roles.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String item : roles.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * @param value 十进制字符串
     * @return 解析结果，非法时返回 0
     */
    private long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * @param value 十进制字符串
     * @return 解析结果，非法时返回 0
     */
    private int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
