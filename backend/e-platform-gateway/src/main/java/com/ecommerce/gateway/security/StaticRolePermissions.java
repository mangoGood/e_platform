package com.ecommerce.gateway.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色 → 权限码的静态兜底映射表。
 *
 * <p>正常情况下 {@link TokenResolver} 从 Redis 的 {@code rbac:role:{code}:perms}
 * 读取权限集合。当 Redis 不可用或缓存未预热时降级到本表，
 * 保证网关不会因 Redis 抖动而全站 403。
 *
 * <p><b>必须与 {@code database/migration-v2.sql} 的 seed 严格一致</b>。
 * 三处（SQL seed / {@link RoutePermissionRegistry} / 本表）不一致会导致
 * Redis 故障时出现权限漂移。
 */
public final class StaticRolePermissions {

    /** 买家角色码。 */
    public static final String ROLE_BUYER = "ROLE_BUYER";

    /** 卖家角色码。 */
    public static final String ROLE_SELLER = "ROLE_SELLER";

    /** 管理员角色码。 */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** 全部 13 个权限码。 */
    private static final Set<String> ALL_PERMISSIONS = unmodifiableSetOf(
            "user:read",
            "product:read", "product:write", "product:delete",
            "cart:manage",
            "order:create", "order:read", "order:manage",
            "comment:create", "comment:reply", "comment:ask", "comment:manage",
            "admin:access");

    /** 角色 → 权限集合。 */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS;

    static {
        Map<String, Set<String>> map = new HashMap<>();
        // ROLE_BUYER：7 个权限，与 migration-v2.sql §1.3 一致
        map.put(ROLE_BUYER, unmodifiableSetOf(
                "user:read", "product:read", "cart:manage",
                "order:create", "order:read",
                "comment:create", "comment:ask"));
        // ROLE_SELLER：11 个权限，与 migration-v2.sql §1.3 一致
        map.put(ROLE_SELLER, unmodifiableSetOf(
                "user:read", "product:read", "product:write", "product:delete", "cart:manage",
                "order:create", "order:read", "order:manage",
                "comment:create", "comment:reply", "comment:ask"));
        // ROLE_ADMIN：全部 13 个权限
        map.put(ROLE_ADMIN, ALL_PERMISSIONS);
        ROLE_PERMISSIONS = Collections.unmodifiableMap(map);
    }

    private StaticRolePermissions() {
        throw new AssertionError("常量类不允许实例化");
    }

    /**
     * 查询单个角色的权限集合。
     *
     * @param roleCode 角色码
     * @return 不可变权限集合；未知角色返回空集
     */
    public static Set<String> permissionsOf(String roleCode) {
        if (roleCode == null) {
            return Collections.emptySet();
        }
        return ROLE_PERMISSIONS.getOrDefault(roleCode.trim(), Collections.emptySet());
    }

    /**
     * 合并多个角色的权限集合。
     *
     * @param roleCodes 角色码集合，可为 null
     * @return 并集，永不为 null
     */
    public static Set<String> permissionsOf(Iterable<String> roleCodes) {
        Set<String> result = new LinkedHashSet<>();
        if (roleCodes == null) {
            return result;
        }
        for (String roleCode : roleCodes) {
            result.addAll(permissionsOf(roleCode));
        }
        return result;
    }

    /**
     * @return 全部已定义的权限码
     */
    public static Set<String> allPermissions() {
        return ALL_PERMISSIONS;
    }

    private static Set<String> unmodifiableSetOf(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }
}
