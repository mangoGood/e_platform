package com.ecommerce.user.dto;

import java.io.Serializable;

/**
 * 登录响应。
 *
 * <h3>为什么是"扁平字段 + 嵌套 user"的冗余结构</h3>
 * 这不是设计不干净，而是<b>刻意的向后兼容层</b>。存量客户端读取的是扁平字段：
 * <ul>
 *   <li>Web 端 {@code frontend/src/views/Login.vue}：
 *       {@code userStore.setToken(res.data.token)} +
 *       {@code userStore.setUserInfo(res.data)}，
 *       随后 {@code stores/user.js} 与各页面读取
 *       {@code userInfo.userType} / {@code userInfo.username} /
 *       {@code userInfo.avatar} / {@code userInfo.userId}
 *       （见 {@code MainLayout.vue}、{@code router/index.js}、{@code SellerProducts.vue}）</li>
 *   <li>Android {@code shared-core/.../model/Auth.kt} 的
 *       {@code data class LoginResponse(token, userId, username, userType, avatar)}，
 *       其中 {@code token/userId/username/userType} 为<b>非空 Kotlin 类型</b> ——
 *       任一字段缺失都会让 kotlinx.serialization 直接抛异常，登录彻底失败。</li>
 * </ul>
 * 因此 {@code token / userId / username / userType / avatar} 五个字段
 * <b>一个都不能删、不能改名</b>。
 *
 * <p>新增字段是安全的：Android 侧 {@code NetworkFactory} 已配置
 * {@code ignoreUnknownKeys = true}，Spring 侧 Jackson 默认
 * {@code FAIL_ON_UNKNOWN_PROPERTIES = false}，多出来的字段会被静默忽略。
 */
public class LoginResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    // ---------------------------------------------------------------------
    // 存量字段：Web 端与 Android 客户端正在读取，禁止删除或改名
    // ---------------------------------------------------------------------

    /** access token。字段名 {@code token} 为历史契约，不可改为 accessToken。 */
    private String token;

    /** 用户 ID。 */
    private Long userId;

    /** 用户名。 */
    private String username;

    /** 用户类型：1-买家，2-卖家。 */
    private Integer userType;

    /** 头像 URL。 */
    private String avatar;

    // ---------------------------------------------------------------------
    // 新增字段：RBAC 与双 Token 体系
    // ---------------------------------------------------------------------

    /** refresh token，用于在 access token 过期后换取新的 access token。 */
    private String refreshToken;

    /** access token 有效期（秒），便于客户端提前调度刷新。 */
    private Long expiresIn;

    /** 嵌套用户对象，携带 roles 与 perms，供新前端做按钮级权限控制。 */
    private UserProfile user;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Integer getUserType() {
        return userType;
    }

    public void setUserType(Integer userType) {
        this.userType = userType;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public Long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(Long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public UserProfile getUser() {
        return user;
    }

    public void setUser(UserProfile user) {
        this.user = user;
    }
}
