package com.ecommerce.gateway.security;

import java.util.Objects;

/**
 * 路由访问策略。
 *
 * <p>由 {@link RoutePermissionRegistry} 依据「路径 + 方法」查表得出，
 * 是网关做放行 / 401 / 403 判定的唯一依据。
 */
public final class AccessDecision {

    /** 策略种类。 */
    public enum Type {
        /** 公开，游客可访问。 */
        PUBLIC,
        /** 需登录，不校验具体权限码。 */
        AUTHENTICATED,
        /** 需持有指定权限码。 */
        PERMISSION,
        /** 仅限内部服务调用，外部请求一律 403。 */
        INTERNAL_DENY
    }

    /** 公开策略单例。 */
    public static final AccessDecision PUBLIC = new AccessDecision(Type.PUBLIC, null);

    /** 需登录策略单例。 */
    public static final AccessDecision AUTHENTICATED = new AccessDecision(Type.AUTHENTICATED, null);

    /** 内部接口拒绝策略单例。 */
    public static final AccessDecision INTERNAL_DENY = new AccessDecision(Type.INTERNAL_DENY, null);

    private final Type type;
    private final String permissionCode;

    private AccessDecision(Type type, String permissionCode) {
        this.type = type;
        this.permissionCode = permissionCode;
    }

    /**
     * 构造「需要指定权限码」的策略。
     *
     * @param permissionCode 权限码，格式 {@code 资源:动作}，不可为空
     * @return 权限策略实例
     */
    public static AccessDecision permission(String permissionCode) {
        Objects.requireNonNull(permissionCode, "permissionCode 不能为 null");
        if (permissionCode.trim().isEmpty()) {
            throw new IllegalArgumentException("permissionCode 不能为空串");
        }
        return new AccessDecision(Type.PERMISSION, permissionCode);
    }

    public Type getType() {
        return type;
    }

    /**
     * @return 权限码；仅当 {@link #getType()} 为 {@link Type#PERMISSION} 时非空
     */
    public String getPermissionCode() {
        return permissionCode;
    }

    @Override
    public String toString() {
        return type == Type.PERMISSION ? "PERMISSION(" + permissionCode + ")" : type.name();
    }
}
