package com.ecommerce.product.service;

import com.ecommerce.common.result.Result;
import com.ecommerce.product.client.UserClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 用户名解析器：{@code userId → username}。
 *
 * <h3>三级取值链（成本从低到高）</h3>
 * <ol>
 *   <li><b>快照</b>：{@code comment.username} 列（写入时落库）—— 零成本，覆盖 99% 场景；</li>
 *   <li><b>Redis 缓存</b>：{@code user:name:{id}}，TTL 30 分钟；</li>
 *   <li><b>Feign 回源</b>：{@code GET /user/info/{id}}，同一批查询内先去重再逐个取。</li>
 * </ol>
 *
 * <p>任何一环失败都<b>不抛异常</b>——用户名只是展示信息，
 * 不能因为 user 服务抖动就让整个评论区 500。取不到时返回 null，
 * 交由 {@code MaskUtil} 兜底成「匿名用户」。
 */
@Service
public class UserNameResolver {

    private static final Logger log = LoggerFactory.getLogger(UserNameResolver.class);

    /** Redis 键前缀。 */
    private static final String CACHE_KEY_PREFIX = "user:name:";

    /** 缓存有效期（分钟）。 */
    private static final long CACHE_TTL_MINUTES = 30L;

    @Autowired
    private UserClient userClient;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 批量解析用户名。
     *
     * @param userIds 用户 id 集合，可为 null / 含 null / 含重复值
     * @return {@code userId → username} 映射，解析失败的 id 不会出现在结果中；永不为 null
     */
    public Map<Long, String> resolveNames(Collection<Long> userIds) {
        Map<Long, String> result = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }

        // 去重 + 剔除非法 id，避免对同一个人重复回源。
        Set<Long> distinctIds = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId != null && userId > 0L) {
                distinctIds.add(userId);
            }
        }

        for (Long userId : distinctIds) {
            String name = resolveName(userId);
            if (name != null) {
                result.put(userId, name);
            }
        }
        return result;
    }

    /**
     * 解析单个用户名（先查缓存，再回源）。
     *
     * @param userId 用户 id
     * @return 用户名，取不到时返回 null
     */
    public String resolveName(Long userId) {
        if (userId == null || userId <= 0L) {
            return null;
        }

        String cached = readCache(userId);
        if (cached != null) {
            return cached;
        }

        String fetched = fetchFromUserService(userId);
        if (fetched != null) {
            writeCache(userId, fetched);
        }
        return fetched;
    }

    /**
     * 读取 Redis 缓存。
     *
     * @param userId 用户 id
     * @return 缓存中的用户名，未命中或 Redis 异常时返回 null
     */
    private String readCache(Long userId) {
        try {
            Object value = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + userId);
            return value instanceof String ? (String) value : null;
        } catch (Exception e) {
            log.warn("读取用户名缓存失败，降级为回源：userId={}, 原因={}", userId, e.toString());
            return null;
        }
    }

    /**
     * 写入 Redis 缓存。
     *
     * @param userId   用户 id
     * @param username 用户名
     */
    private void writeCache(Long userId, String username) {
        try {
            redisTemplate.opsForValue()
                    .set(CACHE_KEY_PREFIX + userId, username, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("写入用户名缓存失败（不影响主流程）：userId={}, 原因={}", userId, e.toString());
        }
    }

    /**
     * 回源 user 服务。
     *
     * @param userId 用户 id
     * @return 用户名，调用失败或用户不存在时返回 null
     */
    private String fetchFromUserService(Long userId) {
        try {
            Result<UserClient.UserBrief> response = userClient.getUserInfo(userId);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                return null;
            }
            String username = response.getData().getUsername();
            return username == null || username.trim().isEmpty() ? null : username;
        } catch (Exception e) {
            log.warn("回源 user 服务取用户名失败：userId={}, 原因={}", userId, e.toString());
            return null;
        }
    }
}
