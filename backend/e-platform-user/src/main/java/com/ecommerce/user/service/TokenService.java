package com.ecommerce.user.service;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Token 生命周期管理：黑名单写入与吊销查询。
 *
 * <h3>黑名单契约（与网关 {@code TokenResolver} 严格对齐）</h3>
 * <ul>
 *   <li>key：{@code auth:blacklist:{jti}} —— 与 {@code TokenResolver.BLACKLIST_KEY_PREFIX} 逐字符一致</li>
 *   <li>判定方式：网关用 {@code redis.hasKey(key)}，因此 <b>value 内容无关紧要</b>，
 *       只要 key 存在即视为已吊销。这里写入 {@code "1"} 仅为便于人工排查。</li>
 *   <li>TTL：等于该 Token 的<b>剩余有效期</b>。Token 自然过期后黑名单记录也随之消失，
 *       Redis 不会无限膨胀 —— 这是"按剩余期设 TTL"而非"固定 TTL"的全部意义。</li>
 * </ul>
 *
 * <h3>为何登出必须写黑名单</h3>
 * JWT 是无状态凭证，服务端不存储会话，客户端"删掉本地 token"只是自欺欺人 ——
 * 攻击者若已窃取该 Token，在剩余的 2 小时里依然畅通无阻。黑名单是 JWT 体系下
 * 实现"立即失效"的唯一手段。
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /** 黑名单 key 前缀，必须与网关 {@code TokenResolver.BLACKLIST_KEY_PREFIX} 一致。 */
    public static final String BLACKLIST_KEY_PREFIX = "auth:blacklist:";

    /** 黑名单占位值。网关只判断 key 是否存在，不读取内容。 */
    private static final String BLACKLIST_VALUE = "1";

    /** {@code Authorization} 头的 Bearer 前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public TokenService(JwtUtil jwtUtil, ObjectProvider<StringRedisTemplate> redisProvider) {
        this.jwtUtil = jwtUtil;
        this.redisProvider = redisProvider;
    }

    /**
     * 从 {@code Authorization} 头中取出裸 Token。
     *
     * @param authorizationHeader 形如 {@code Bearer xxx.yyy.zzz} 的头值，可为 null
     * @return 裸 Token 字符串
     * @throws BusinessException 头缺失或格式非法时抛 401
     */
    public String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录，请先登录");
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录，请先登录");
        }
        return token;
    }

    /**
     * 吊销一个 Token：把它的 jti 写入黑名单，TTL 取其剩余有效期。
     *
     * <p>对已过期或无法解析的 Token 直接返回 false —— 它们本就无效，
     * 再写一条 TTL 为 0 的记录没有意义。
     *
     * @param token 紧凑格式的 JWT
     * @return 成功写入黑名单返回 true
     */
    public boolean revoke(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        String jti;
        long remainingMillis;
        try {
            jti = jwtUtil.getJti(token);
            remainingMillis = jwtUtil.getRemainingMillis(token);
        } catch (Exception e) {
            log.debug("待吊销 Token 无法解析，视为已失效: {}", e.getMessage());
            return false;
        }
        if (jti == null || jti.isEmpty()) {
            // 极老的 Token 可能没有 jti，无法定位到具体凭证，只能依赖其自然过期。
            log.warn("Token 缺少 jti，无法加入黑名单");
            return false;
        }
        if (remainingMillis <= 0L) {
            log.debug("Token 已自然过期，无需加入黑名单: jti={}", jti);
            return false;
        }

        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            // 必须显式失败：如果登出接口在 Redis 挂掉时返回成功，用户会误以为
            // "我已经安全登出了"，而那个 Token 实际上还能再用 2 小时。
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "登出服务暂不可用，请稍后重试");
        }
        try {
            redis.opsForValue().set(BLACKLIST_KEY_PREFIX + jti, BLACKLIST_VALUE,
                    remainingMillis, TimeUnit.MILLISECONDS);
            log.info("Token 已吊销: jti={}, 剩余有效期={} 秒", jti, remainingMillis / 1000L);
            return true;
        } catch (Exception e) {
            log.error("写入 Token 黑名单失败: jti={}, err={}", jti, e.getMessage());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "登出服务暂不可用，请稍后重试");
        }
    }

    /**
     * 查询 jti 是否已被吊销。
     *
     * <p>与网关的 fail-open 策略保持一致：Redis 不可用时返回 false（放行），
     * 避免缓存故障导致全站无法刷新 Token。
     *
     * @param jti Token 唯一标识
     * @return 命中黑名单返回 true
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isEmpty()) {
            return false;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(BLACKLIST_KEY_PREFIX + jti));
        } catch (Exception e) {
            log.warn("查询 Token 黑名单失败，本次放行: jti={}, err={}", jti, e.getMessage());
            return false;
        }
    }
}
