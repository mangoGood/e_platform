package com.ecommerce.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 从请求中解析出可信身份。
 *
 * <h3>解析规则</h3>
 * <ol>
 *   <li>无 {@code Authorization: Bearer } 头 → {@link AuthPrincipal#ANONYMOUS}</li>
 *   <li>JWT 验签失败 / 已过期 → {@link AuthPrincipal#ANONYMOUS}
 *       （由 {@link RoutePermissionRegistry} 决定该匿名身份能否访问目标路由）</li>
 *   <li>Token 类型不是 {@code access}（例如拿 refresh token 当访问凭证）→ ANONYMOUS</li>
 *   <li><b>jti 命中 Redis 黑名单 → 直接抛 {@link TokenRevokedException}，401</b>
 *       —— 已登出的 Token 必须明确报错，而不是静默降级为游客</li>
 * </ol>
 *
 * <h3>权限集合来源</h3>
 * JWT 的 {@code roles} claim 只带角色码（体积小）。权限码按角色从 Redis
 * {@code rbac:role:{code}:perms} 读取（由 user 服务启动时预热）；
 * Redis 不可用或未命中时降级到 {@link StaticRolePermissions}，
 * 保证网关不因 Redis 抖动而全站 403。
 */
@Component
public class TokenResolver {

    private static final Logger log = LoggerFactory.getLogger(TokenResolver.class);

    /** Token 黑名单 key 前缀。 */
    public static final String BLACKLIST_KEY_PREFIX = "auth:blacklist:";

    /** 角色权限缓存 key 模板。 */
    public static final String ROLE_PERMS_KEY_PREFIX = "rbac:role:";

    /** 角色权限缓存 key 后缀。 */
    public static final String ROLE_PERMS_KEY_SUFFIX = ":perms";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_USER_TYPE = "userType";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_TOKEN_TYPE = "typ";
    private static final String TOKEN_TYPE_ACCESS = "access";

    @Value("${jwt.secret}")
    private String jwtSecret;

    /** Redis 可能不可用，用 ObjectProvider 延迟获取并允许缺失。 */
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public TokenResolver(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /**
     * Token 已被吊销（登出）时抛出，由 ProxyService 转换为 401。
     */
    public static class TokenRevokedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TokenRevokedException(String message) {
            super(message);
        }
    }

    /**
     * 解析当前请求的身份。
     *
     * @param request 原始请求
     * @return 可信身份；无有效凭证时返回 {@link AuthPrincipal#ANONYMOUS}
     * @throws TokenRevokedException Token 的 jti 命中黑名单
     */
    public AuthPrincipal resolve(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return AuthPrincipal.ANONYMOUS;
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return AuthPrincipal.ANONYMOUS;
        }

        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setSigningKey(getSecretKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            // 过期/篡改/格式错误统一视为匿名，由路由策略决定是否放行。
            log.debug("JWT 解析失败，按匿名处理: {}", e.getMessage());
            return AuthPrincipal.ANONYMOUS;
        }

        // 拒绝用 refresh token 直接访问业务接口。
        Object tokenType = claims.get(CLAIM_TOKEN_TYPE);
        if (tokenType != null && !TOKEN_TYPE_ACCESS.equals(String.valueOf(tokenType))) {
            log.debug("非 access 类型 Token（typ={}），按匿名处理", tokenType);
            return AuthPrincipal.ANONYMOUS;
        }

        String jti = claims.getId();
        if (jti != null && isBlacklisted(jti)) {
            throw new TokenRevokedException("登录状态已失效，请重新登录");
        }

        Long userId = readLong(claims, CLAIM_USER_ID);
        if (userId == null || userId <= 0L) {
            // 没有 userId claim 的 Token 无法确定身份，绝不能放行为"某个用户"。
            log.debug("Token 缺少有效 userId claim，按匿名处理");
            return AuthPrincipal.ANONYMOUS;
        }
        Integer userType = readInt(claims, CLAIM_USER_TYPE);
        String username = claims.get(CLAIM_USERNAME) == null
                ? claims.getSubject()
                : String.valueOf(claims.get(CLAIM_USERNAME));

        Set<String> roles = readRoles(claims);
        Set<String> perms = resolvePermissions(roles);

        return new AuthPrincipal(userId, userType == null ? 0 : userType, username, roles, perms, jti);
    }

    /**
     * 查询 jti 是否已被登出。
     *
     * <p>Redis 不可用时<b>放行</b>（fail-open）：黑名单是"额外的失效加速"，
     * 若因缓存故障导致全站不可登录，可用性代价大于安全收益；Token 本身仍受 2 小时有效期约束。
     *
     * @param jti Token 唯一标识
     * @return 命中黑名单返回 true
     */
    private boolean isBlacklisted(String jti) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(BLACKLIST_KEY_PREFIX + jti));
        } catch (Exception e) {
            log.warn("查询 Token 黑名单失败，本次放行: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 按角色汇总权限码。
     *
     * @param roles 角色码集合
     * @return 权限码并集，永不为 null
     */
    private Set<String> resolvePermissions(Set<String> roles) {
        Set<String> perms = new LinkedHashSet<>();
        if (roles.isEmpty()) {
            return perms;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        for (String role : roles) {
            Set<String> fromCache = null;
            if (redis != null) {
                try {
                    fromCache = redis.opsForSet().members(ROLE_PERMS_KEY_PREFIX + role + ROLE_PERMS_KEY_SUFFIX);
                } catch (Exception e) {
                    log.warn("读取角色权限缓存失败，降级为静态映射表: role={}, err={}", role, e.getMessage());
                }
            }
            if (fromCache == null || fromCache.isEmpty()) {
                perms.addAll(StaticRolePermissions.permissionsOf(role));
            } else {
                perms.addAll(fromCache);
            }
        }
        return perms;
    }

    /**
     * @param claims JWT 载荷
     * @return 角色码集合（字典序），缺失时为空集
     */
    @SuppressWarnings("unchecked")
    private Set<String> readRoles(Claims claims) {
        Object raw = claims.get(CLAIM_ROLES);
        Set<String> roles = new TreeSet<>();
        if (raw instanceof Collection) {
            for (Object item : (Collection<Object>) raw) {
                if (item != null) {
                    String value = String.valueOf(item).trim();
                    if (!value.isEmpty()) {
                        roles.add(value);
                    }
                }
            }
        }
        return roles;
    }

    /**
     * 读取 Long 型 claim，兼容 JSON 反序列化出的 Integer。
     *
     * @param claims JWT 载荷
     * @param name   claim 名
     * @return 数值；缺失或非数字时返回 null
     */
    private Long readLong(Claims claims, String name) {
        Object value = claims.get(name);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 读取 Integer 型 claim。
     *
     * @param claims JWT 载荷
     * @param name   claim 名
     * @return 数值；缺失或非数字时返回 null
     */
    private Integer readInt(Claims claims, String name) {
        Long value = readLong(claims, name);
        return value == null ? null : value.intValue();
    }

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }
}
