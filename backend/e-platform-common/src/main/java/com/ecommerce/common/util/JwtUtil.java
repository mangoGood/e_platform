package com.ecommerce.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 签发与解析工具。
 *
 * <h3>Token 生命周期</h3>
 * <ul>
 *   <li><b>access</b>：默认 2 小时（{@code jwt.access-expiration}），带 {@code roles}，{@code typ=access}</li>
 *   <li><b>refresh</b>：默认 7 天（{@code jwt.refresh-expiration}），<b>不带 roles</b>
 *       （避免提权/降权后 refresh 仍沿用旧权限），{@code typ=refresh}</li>
 * </ul>
 *
 * <p>两类 Token 均带 {@code jti}，登出时写入 Redis 黑名单 {@code auth:blacklist:{jti}}。
 *
 * <p><b>兼容性</b>：保留旧的 {@link #generateToken(Long, String, Integer)} 方法签名，
 * mobile BFF 与存量代码仍可直接使用。
 */
@Component
public class JwtUtil {

    /** Token 类型 claim 名。 */
    public static final String CLAIM_TOKEN_TYPE = "typ";

    /** 角色列表 claim 名。 */
    public static final String CLAIM_ROLES = "roles";

    /** 用户 ID claim 名。 */
    public static final String CLAIM_USER_ID = "userId";

    /** 用户类型 claim 名。 */
    public static final String CLAIM_USER_TYPE = "userType";

    /** 用户名 claim 名。 */
    public static final String CLAIM_USERNAME = "username";

    /** access token 的 typ 值。 */
    public static final String TOKEN_TYPE_ACCESS = "access";

    /** refresh token 的 typ 值。 */
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    @Value("${jwt.secret}")
    private String jwtSecret;

    /** 旧版通用过期时间，仅供 {@link #generateToken} 使用。 */
    @Value("${jwt.expiration:604800000}")
    private Long expirationTime;

    /** access token 有效期（毫秒），默认 2 小时。 */
    @Value("${jwt.access-expiration:7200000}")
    private Long accessExpiration;

    /** refresh token 有效期（毫秒），默认 7 天。 */
    @Value("${jwt.refresh-expiration:604800000}")
    private Long refreshExpiration;

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    /**
     * 签发旧版 Token（保留以兼容存量调用方）。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param userType 用户类型
     * @return 紧凑格式的 JWT
     */
    public String generateToken(Long userId, String username, Integer userType) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_USER_ID, userId);
        claims.put(CLAIM_USERNAME, username);
        claims.put(CLAIM_USER_TYPE, userType);

        return Jwts.builder()
                .setClaims(claims)
                .setId(UUID.randomUUID().toString())
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(getSecretKey())
                .compact();
    }

    /**
     * 签发 access token。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param userType 用户类型
     * @param roles    角色码集合，可为 null
     * @return 紧凑格式的 JWT
     */
    public String generateAccessToken(Long userId, String username, Integer userType,
                                      Collection<String> roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_USER_ID, userId);
        claims.put(CLAIM_USERNAME, username);
        claims.put(CLAIM_USER_TYPE, userType);
        claims.put(CLAIM_ROLES, roles == null ? Collections.emptyList() : new ArrayList<>(roles));
        claims.put(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS);

        return buildToken(claims, username, accessExpiration);
    }

    /**
     * 签发 refresh token（不携带角色，换取 access 时重新查库）。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param userType 用户类型
     * @return 紧凑格式的 JWT
     */
    public String generateRefreshToken(Long userId, String username, Integer userType) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_USER_ID, userId);
        claims.put(CLAIM_USERNAME, username);
        claims.put(CLAIM_USER_TYPE, userType);
        claims.put(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH);

        return buildToken(claims, username, refreshExpiration);
    }

    /**
     * 统一的 Token 构建入口。
     *
     * @param claims       载荷
     * @param subject      主题（用户名）
     * @param ttlMillis    有效期（毫秒）
     * @return 紧凑格式的 JWT
     */
    private String buildToken(Map<String, Object> claims, String subject, long ttlMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setClaims(claims)
                .setId(UUID.randomUUID().toString())
                .setSubject(subject)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttlMillis))
                .signWith(getSecretKey())
                .compact();
    }

    /**
     * 解析并验签 Token。
     *
     * @param token 紧凑格式的 JWT
     * @return 载荷
     * @throws io.jsonwebtoken.JwtException 验签失败或已过期
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSecretKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * @param token 紧凑格式的 JWT
     * @return 验签通过且未过期时返回 true
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseToken(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return parseToken(token).get(CLAIM_USER_ID, Long.class);
    }

    public String getUsername(String token) {
        return parseToken(token).getSubject();
    }

    public Integer getUserType(String token) {
        return parseToken(token).get(CLAIM_USER_TYPE, Integer.class);
    }

    /**
     * @param token 紧凑格式的 JWT
     * @return Token 唯一标识 jti，缺失时返回 null
     */
    public String getJti(String token) {
        return parseToken(token).getId();
    }

    /**
     * @param token 紧凑格式的 JWT
     * @return Token 类型（{@code access} / {@code refresh}），旧版 Token 无该 claim 时返回 {@code access}
     */
    public String getTokenType(String token) {
        Object type = parseToken(token).get(CLAIM_TOKEN_TYPE);
        return type == null ? TOKEN_TYPE_ACCESS : String.valueOf(type);
    }

    /**
     * 读取角色列表。
     *
     * @param token 紧凑格式的 JWT
     * @return 角色码列表，缺失时返回空列表（永不为 null）
     */
    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        Object raw = parseToken(token).get(CLAIM_ROLES);
        if (!(raw instanceof Collection)) {
            return Collections.emptyList();
        }
        List<String> roles = new ArrayList<>();
        for (Object item : (Collection<Object>) raw) {
            if (item != null) {
                roles.add(String.valueOf(item));
            }
        }
        return roles;
    }

    /**
     * 计算 Token 的剩余有效期，用于设置黑名单 TTL。
     *
     * @param token 紧凑格式的 JWT
     * @return 剩余毫秒数；已过期或解析失败时返回 0
     */
    public long getRemainingMillis(String token) {
        try {
            Date expiration = parseToken(token).getExpiration();
            if (expiration == null) {
                return 0L;
            }
            return Math.max(0L, expiration.getTime() - System.currentTimeMillis());
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * @return access token 有效期（秒），用于登录响应中的 {@code expiresIn}
     */
    public long getAccessExpirationSeconds() {
        return accessExpiration / 1000L;
    }
}
