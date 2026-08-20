package com.ecommerce.user.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 登录响应中的嵌套用户对象，对应 {@code data.user}。
 *
 * <p>这是新前端读取 {@code roles} / {@code perms} 的位置。旧客户端读的是
 * {@link LoginResponse} 上的扁平字段，二者并存，互不影响。
 */
public class UserProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID。 */
    private Long id;

    /** 用户名。 */
    private String username;

    /** 用户类型：1-买家，2-卖家。保留以兼容按 userType 做界面分支的存量逻辑。 */
    private Integer userType;

    /** 头像 URL。 */
    private String avatar;

    /** 角色码列表，如 {@code ["ROLE_BUYER"]}。 */
    private List<String> roles = new ArrayList<>();

    /** 权限码列表，如 {@code ["product:read", "cart:manage"]}。 */
    private List<String> perms = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public List<String> getRoles() {
        return roles;
    }

    /**
     * @param roles 角色码列表，null 时按空列表处理（前端可无条件 {@code .includes()}）
     */
    public void setRoles(List<String> roles) {
        this.roles = roles == null ? Collections.emptyList() : roles;
    }

    public List<String> getPerms() {
        return perms;
    }

    /**
     * @param perms 权限码列表，null 时按空列表处理
     */
    public void setPerms(List<String> perms) {
        this.perms = perms == null ? Collections.emptyList() : perms;
    }
}
