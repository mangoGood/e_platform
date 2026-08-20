package com.ecommerce.user.service;

import com.ecommerce.user.entity.Role;
import com.ecommerce.user.mapper.PermissionMapper;
import com.ecommerce.user.mapper.RoleMapper;
import com.ecommerce.user.mapper.UserRoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * RBAC 查询与缓存服务。
 *
 * <h3>缓存契约（与网关 {@code TokenResolver} 严格对齐，改动必须两边同步）</h3>
 * <ul>
 *   <li>{@code rbac:role:{roleCode}:perms} —— <b>Redis Set 类型</b>，TTL {@value #CACHE_TTL_SECONDS} 秒。
 *       网关用 {@code opsForSet().members(key)} 读取，因此这里<b>必须</b>用
 *       {@code opsForSet().add(...)} 写入。若误用 {@code opsForValue().set(...)}
 *       写成 String，网关侧读取会抛 {@code WRONGTYPE} 并静默降级到静态兜底表，
 *       表面"能用"但权限实际来自硬编码，DB 改权限不生效 —— 这类 Bug 极难排查。</li>
 *   <li>{@code rbac:user:{userId}:perms} —— Redis Set 类型，TTL 同上，仅本服务自用。</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * Redis 不可用时全部退化为直接查库（fail-open），只损失性能不损失正确性。
 * 网关侧则退化到 {@code StaticRolePermissions} 静态表，二者结果一致。
 */
@Service
public class RbacService {

    private static final Logger log = LoggerFactory.getLogger(RbacService.class);

    /** 角色权限缓存 key 前缀，必须与网关 {@code TokenResolver.ROLE_PERMS_KEY_PREFIX} 一致。 */
    public static final String ROLE_PERMS_KEY_PREFIX = "rbac:role:";

    /** 角色权限缓存 key 后缀，必须与网关 {@code TokenResolver.ROLE_PERMS_KEY_SUFFIX} 一致。 */
    public static final String ROLE_PERMS_KEY_SUFFIX = ":perms";

    /** 用户权限缓存 key 前缀。 */
    public static final String USER_PERMS_KEY_PREFIX = "rbac:user:";

    /** 用户权限缓存 key 后缀。 */
    public static final String USER_PERMS_KEY_SUFFIX = ":perms";

    /** 缓存有效期（秒）。 */
    public static final long CACHE_TTL_SECONDS = 600L;

    /** 用户类型：买家。 */
    public static final int USER_TYPE_BUYER = 1;

    /** 用户类型：卖家。 */
    public static final int USER_TYPE_SELLER = 2;

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;

    /** Redis 可能不可用，用 ObjectProvider 允许缺失，避免拖垮登录主链路。 */
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public RbacService(RoleMapper roleMapper,
                       PermissionMapper permissionMapper,
                       UserRoleMapper userRoleMapper,
                       ObjectProvider<StringRedisTemplate> redisProvider) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.redisProvider = redisProvider;
    }

    /**
     * 查询用户持有的角色码。
     *
     * <p>刻意<b>不做缓存</b>：角色是签发 access token 时写进 {@code roles} claim 的内容，
     * 一旦缓存旧值，管理员刚做的提权/降权要等缓存过期才生效，且 Token 有效期长达 2 小时，
     * 实际延迟会叠加成"最长 2 小时 + 10 分钟"。单表 JOIN 查询成本极低，不值得为此冒险。
     *
     * @param userId 用户 ID，可为 null
     * @return 角色码列表（字典序），永不为 null
     */
    public List<String> getUserRoles(Long userId) {
        if (userId == null || userId <= 0L) {
            return Collections.emptyList();
        }
        List<String> roles = roleMapper.selectRoleCodesByUserId(userId);
        return roles == null ? Collections.emptyList() : roles;
    }

    /**
     * 查询用户的权限码并集，优先读 Redis 缓存。
     *
     * @param userId 用户 ID，可为 null
     * @return 权限码列表（字典序），永不为 null
     */
    public List<String> getUserPermissions(Long userId) {
        if (userId == null || userId <= 0L) {
            return Collections.emptyList();
        }
        String key = USER_PERMS_KEY_PREFIX + userId + USER_PERMS_KEY_SUFFIX;
        List<String> cached = readSet(key);
        if (cached != null) {
            return cached;
        }
        List<String> perms = permissionMapper.selectCodesByUserId(userId);
        perms = perms == null ? Collections.emptyList() : perms;
        writeSet(key, perms);
        return perms;
    }

    /**
     * 查询单个角色的权限码，优先读 Redis 缓存。
     *
     * @param roleCode 角色码
     * @return 权限码列表（字典序），永不为 null
     */
    public List<String> getRolePermissions(String roleCode) {
        if (roleCode == null || roleCode.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String normalized = roleCode.trim();
        String key = ROLE_PERMS_KEY_PREFIX + normalized + ROLE_PERMS_KEY_SUFFIX;
        List<String> cached = readSet(key);
        if (cached != null) {
            return cached;
        }
        List<String> perms = permissionMapper.selectCodesByRoleCode(normalized);
        perms = perms == null ? Collections.emptyList() : perms;
        writeSet(key, perms);
        return perms;
    }

    /**
     * 按用户类型绑定默认角色（注册时调用）。
     *
     * <p>{@code user_type} 字段<b>保留不动</b>，本方法只是在 RBAC 表中补一条等价映射，
     * 使新注册用户与 {@code migration-v2.sql} 回填的存量用户走同一套鉴权路径。
     *
     * @param userId   用户 ID
     * @param userType 用户类型：1-买家，2-卖家
     * @return 实际绑定的角色码；未能绑定时返回 null
     */
    public String assignDefaultRole(Long userId, Integer userType) {
        if (userId == null || userId <= 0L) {
            return null;
        }
        String roleCode = defaultRoleCodeOf(userType);
        Long roleId = roleMapper.selectIdByCode(roleCode);
        if (roleId == null) {
            // 角色表未初始化（migration-v2.sql 未执行）。注册本身不应因此失败，
            // 但必须留下明确告警，否则新用户会以"零权限"状态静默上线。
            log.error("角色 {} 不存在，用户 {} 未绑定任何角色，请确认 migration-v2.sql 已执行",
                    roleCode, userId);
            return null;
        }
        userRoleMapper.insertIgnore(userId, roleId);
        evictUserPermissions(userId);
        log.info("用户 {} 绑定默认角色 {}", userId, roleCode);
        return roleCode;
    }

    /**
     * 用户类型到默认角色码的映射。
     *
     * @param userType 用户类型
     * @return 角色码；未知类型一律按最小权限的买家处理
     */
    public String defaultRoleCodeOf(Integer userType) {
        if (userType != null && userType == USER_TYPE_SELLER) {
            return Role.CODE_SELLER;
        }
        // 默认拒绝原则的体现：未知 user_type 给最小权限角色，而不是给管理员。
        return Role.CODE_BUYER;
    }

    /**
     * 失效某个用户的权限缓存。角色变更后必须调用。
     *
     * @param userId 用户 ID
     */
    public void evictUserPermissions(Long userId) {
        if (userId == null) {
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.delete(USER_PERMS_KEY_PREFIX + userId + USER_PERMS_KEY_SUFFIX);
        } catch (Exception e) {
            log.warn("清理用户权限缓存失败: userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 启动后预热全部角色的权限缓存。
     *
     * <p>网关只有 {@code roles} claim，权限码要靠 {@code rbac:role:{code}:perms} 反查。
     * 若不预热，网关在缓存未命中时会降级到静态兜底表 —— 结果虽然一致，
     * 但"DB 里改了权限却不生效"会变成一个隐蔽的伪 Bug。预热让 DB 成为唯一事实来源。
     *
     * <p>预热失败<b>不阻断启动</b>：Redis 抖动不应导致用户服务起不来，
     * 网关侧有静态兜底表保证可用性。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpRoleCache() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.warn("Redis 不可用，跳过角色权限缓存预热；网关将使用静态兜底表");
            return;
        }
        try {
            List<String> roleCodes = roleMapper.selectAllActiveCodes();
            if (roleCodes == null || roleCodes.isEmpty()) {
                log.warn("role 表为空，跳过权限缓存预热，请确认 migration-v2.sql 已执行");
                return;
            }
            int warmed = 0;
            for (String roleCode : roleCodes) {
                List<String> perms = permissionMapper.selectCodesByRoleCode(roleCode);
                if (perms != null && !perms.isEmpty()) {
                    writeSet(ROLE_PERMS_KEY_PREFIX + roleCode + ROLE_PERMS_KEY_SUFFIX, perms);
                    warmed++;
                }
                log.info("角色权限预热: {} -> {} 个权限", roleCode,
                        perms == null ? 0 : perms.size());
            }
            log.info("角色权限缓存预热完成：{}/{} 个角色，TTL {} 秒",
                    warmed, roleCodes.size(), CACHE_TTL_SECONDS);
        } catch (Exception e) {
            log.warn("角色权限缓存预热失败，网关将降级使用静态兜底表: {}", e.getMessage());
        }
    }

    /**
     * 读取 Redis Set 类型缓存。
     *
     * @param key 缓存 key
     * @return 命中时返回字典序列表；未命中或 Redis 不可用时返回 <b>null</b>
     *         （用 null 而非空列表区分"没缓存"与"缓存了一个空集"）
     */
    private List<String> readSet(String key) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return null;
        }
        try {
            Set<String> members = redis.opsForSet().members(key);
            if (members == null || members.isEmpty()) {
                return null;
            }
            List<String> result = new ArrayList<>(new LinkedHashSet<>(members));
            Collections.sort(result);
            return result;
        } catch (Exception e) {
            log.warn("读取权限缓存失败，降级查库: key={}, err={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 以 Set 类型写入 Redis 并设置 TTL。
     *
     * <p>先 {@code delete} 再 {@code add}：直接 add 只会做并集，
     * 权限被回收时旧成员会残留在缓存里，造成"删了权限还能用"。
     *
     * @param key    缓存 key
     * @param values 成员集合；为空时只删除不写入（Redis 无法表达空 Set）
     */
    private void writeSet(String key, List<String> values) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.delete(key);
            if (values == null || values.isEmpty()) {
                return;
            }
            redis.opsForSet().add(key, values.toArray(new String[0]));
            redis.expire(key, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入权限缓存失败，本次跳过: key={}, err={}", key, e.getMessage());
        }
    }
}
