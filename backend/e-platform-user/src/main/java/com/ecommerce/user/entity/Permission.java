package com.ecommerce.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 权限实体，对应 {@code permission} 表（由 migration-v2.sql 创建）。
 *
 * <p>权限码格式固定为 {@code 资源:动作}，全小写，如 {@code product:write}。
 *
 * <p><b>维护提醒</b>：新增权限码必须同步三处 ——
 * {@code migration-v2.sql} 的 seed、网关 {@code RoutePermissionRegistry} 的路由规则、
 * 网关 {@code StaticRolePermissions} 的兜底表。漏一处就会出现"配了权限却仍然 403"。
 */
@TableName("permission")
public class Permission implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 权限码，如 {@code product:write}。 */
    private String code;

    /** 权限名称。 */
    private String name;

    /** 资源域，如 {@code product}。 */
    private String resource;

    /** 动作，如 {@code write}。 */
    private String action;

    /** 权限描述。 */
    private String description;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
