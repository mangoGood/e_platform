package com.ecommerce.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色实体，对应 {@code role} 表（由 migration-v2.sql 创建）。
 *
 * <p>内置三个角色：{@code ROLE_BUYER} / {@code ROLE_SELLER} / {@code ROLE_ADMIN}。
 * 角色码全局唯一（{@code uk_role_code}），业务代码一律用 {@code code} 而非 {@code id} 引用角色。
 */
@TableName("role")
public class Role implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 买家角色码。 */
    public static final String CODE_BUYER = "ROLE_BUYER";

    /** 卖家角色码。 */
    public static final String CODE_SELLER = "ROLE_SELLER";

    /** 管理员角色码。 */
    public static final String CODE_ADMIN = "ROLE_ADMIN";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码，如 {@code ROLE_BUYER}。 */
    private String code;

    /** 角色名称。 */
    private String name;

    /** 角色描述。 */
    private String description;

    /** 状态：0-禁用，1-正常。 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
