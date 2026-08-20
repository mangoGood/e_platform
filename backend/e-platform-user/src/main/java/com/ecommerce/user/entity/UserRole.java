package com.ecommerce.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户-角色关联实体，对应 {@code user_role} 表（由 migration-v2.sql 创建）。
 *
 * <p>唯一键 {@code uk_user_role(user_id, role_id)} 保证同一用户不会重复绑定同一角色，
 * 因此写入一律走 {@code INSERT IGNORE}（见 {@code UserRoleMapper#insertIgnore}）。
 *
 * <p>注意：{@code user} 表的 {@code user_type} 字段<b>继续保留</b>，
 * 存量前端与 Android 客户端仍在读取它。本表是 user_type 的超集而非替代品 ——
 * 例如 {@code admin} 账号 {@code user_type = 1}，却同时持有
 * {@code ROLE_BUYER} 与 {@code ROLE_ADMIN} 两个角色。
 */
@TableName("user_role")
public class UserRole implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID。 */
    private Long userId;

    /** 角色 ID。 */
    private Long roleId;

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
