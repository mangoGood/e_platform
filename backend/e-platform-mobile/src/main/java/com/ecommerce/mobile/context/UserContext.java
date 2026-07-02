package com.ecommerce.mobile.context;

/**
 * 用户上下文持有者（ThreadLocal）
 * <p>
 * 在拦截器中解析 JWT 后写入，Service / Controller 通过静态方法获取当前用户。
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> USER_TYPE = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    public static void set(Long userId, Integer userType, String username) {
        USER_ID.set(userId);
        USER_TYPE.set(userType);
        USERNAME.set(username);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static Integer getUserType() {
        return USER_TYPE.get();
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static void clear() {
        USER_ID.remove();
        USER_TYPE.remove();
        USERNAME.remove();
    }
}
