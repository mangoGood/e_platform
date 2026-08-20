package com.ecommerce.common.security;

import java.util.Collections;
import java.util.List;

/**
 * 网关下发身份的线程上下文。
 *
 * <p>由 {@link GatewaySignatureInterceptor} 在验签通过后写入，
 * 并在 {@code afterCompletion} 阶段清理。业务代码可通过静态方法读取
 * <b>已被签名保护</b>的身份信息，而不必再直接信任 {@code @RequestHeader("X-User-Id")}。
 *
 * <p>注意：存量控制器仍使用 {@code @RequestHeader("X-User-Id")}。这是安全的——
 * 因为该头此刻已被网关签名覆盖，任何伪造都会在拦截器处被拒绝。本上下文为
 * 后续新代码提供更清晰的读取方式。
 */
public final class GatewayUserContext {

    /** 匿名用户 ID / 类型的占位值。 */
    public static final long ANONYMOUS_ID = 0L;

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> USER_TYPE = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> ROLES = new ThreadLocal<>();

    private GatewayUserContext() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 写入当前线程的身份信息。
     *
     * @param userId   用户 ID，匿名为 0
     * @param userType 用户类型，匿名为 0
     * @param roles    角色码列表，匿名为空列表
     */
    public static void set(Long userId, Integer userType, List<String> roles) {
        USER_ID.set(userId == null ? ANONYMOUS_ID : userId);
        USER_TYPE.set(userType == null ? 0 : userType);
        ROLES.set(roles == null ? Collections.emptyList() : Collections.unmodifiableList(roles));
    }

    /**
     * @return 当前用户 ID，未设置时返回 0
     */
    public static long getUserId() {
        Long value = USER_ID.get();
        return value == null ? ANONYMOUS_ID : value;
    }

    /**
     * @return 当前用户类型，未设置时返回 0
     */
    public static int getUserType() {
        Integer value = USER_TYPE.get();
        return value == null ? 0 : value;
    }

    /**
     * @return 当前角色码列表，未设置时返回空列表（永不为 null）
     */
    public static List<String> getRoles() {
        List<String> value = ROLES.get();
        return value == null ? Collections.emptyList() : value;
    }

    /**
     * @return 当前请求是否为匿名请求
     */
    public static boolean isAnonymous() {
        return getUserId() == ANONYMOUS_ID;
    }

    /**
     * @param roleCode 角色码，如 {@code ROLE_SELLER}
     * @return 当前用户是否持有该角色
     */
    public static boolean hasRole(String roleCode) {
        return roleCode != null && getRoles().contains(roleCode);
    }

    /** 清理当前线程的所有上下文，必须在请求结束时调用，防止线程池串号。 */
    public static void clear() {
        USER_ID.remove();
        USER_TYPE.remove();
        ROLES.remove();
    }
}
