package com.ecommerce.gateway.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 网关侧的不可变身份对象。
 *
 * <p>由 {@link TokenResolver} 从 JWT 解析产出，是整条安全管线中唯一可信的身份来源。
 * 客户端传入的任何 {@code X-User-*} 头都与本对象无关（会被 HeaderSanitizer 剥离）。
 */
public final class AuthPrincipal {

    /** 匿名占位 ID。 */
    public static final long ANONYMOUS_ID = 0L;

    /** 匿名占位类型。 */
    public static final int ANONYMOUS_TYPE = 0;

    /** 匿名单例：未携带 Token、Token 解析失败或非 access 类型时统一使用。 */
    public static final AuthPrincipal ANONYMOUS = new AuthPrincipal(
            ANONYMOUS_ID, ANONYMOUS_TYPE, null, Collections.emptySet(), Collections.emptySet(), null);

    private final long userId;
    private final int userType;
    private final String username;
    private final Set<String> roles;
    private final Set<String> perms;
    private final String jti;

    /**
     * @param userId   用户 ID，匿名为 0
     * @param userType 用户类型，匿名为 0
     * @param username 用户名，匿名为 null
     * @param roles    角色码集合，内部会做去重与字典序排序
     * @param perms    权限码集合
     * @param jti      Token 唯一标识，匿名为 null
     */
    public AuthPrincipal(long userId, int userType, String username,
                         Set<String> roles, Set<String> perms, String jti) {
        this.userId = userId;
        this.userType = userType;
        this.username = username;
        // TreeSet 保证角色天然按字典序排列，签名原文拼接时无需再排序。
        this.roles = roles == null || roles.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new TreeSet<>(roles));
        this.perms = perms == null || perms.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(perms));
        this.jti = jti;
    }

    /**
     * @return 是否为匿名身份
     */
    public boolean isAnonymous() {
        return userId == ANONYMOUS_ID;
    }

    /**
     * @param permissionCode 权限码，如 {@code product:write}
     * @return 是否持有该权限
     */
    public boolean hasPermission(String permissionCode) {
        return permissionCode != null && perms.contains(permissionCode);
    }

    /**
     * 角色码按字典序升序、英文逗号连接、无空格 —— 与 HMAC 签名原文要求一致。
     *
     * @return 角色串，匿名时为空串
     */
    public String rolesJoined() {
        return roles.isEmpty() ? "" : String.join(",", roles);
    }

    public long getUserId() {
        return userId;
    }

    public int getUserType() {
        return userType;
    }

    public String getUsername() {
        return username;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public Set<String> getPerms() {
        return perms;
    }

    public String getJti() {
        return jti;
    }

    /**
     * @return 角色码列表快照，便于日志输出
     */
    public List<String> roleList() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(roles));
    }

    @Override
    public String toString() {
        return "AuthPrincipal{userId=" + userId + ", userType=" + userType
                + ", username='" + username + "', roles=" + roles + '}';
    }
}
