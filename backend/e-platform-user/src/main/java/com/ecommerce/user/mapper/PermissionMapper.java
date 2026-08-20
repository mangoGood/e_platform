package com.ecommerce.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.user.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 权限数据访问。
 *
 * <p>权限码格式固定为 {@code 资源:动作}（全小写），必须与三处保持字面量一致：
 * {@code database/migration-v2.sql} 的 seed、网关 {@code RoutePermissionRegistry}
 * 的路由规则、网关 {@code StaticRolePermissions} 的降级兜底表。
 *
 * <p>同上，{@code @Select} 手写 SQL 不受 {@code @TableLogic} 影响，
 * 逻辑删除条件必须显式书写。
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * 查询单个角色拥有的权限码。
     *
     * <p>结果写入 Redis {@code rbac:role:{code}:perms}，供网关 {@code TokenResolver} 读取。
     *
     * @param roleCode 角色码，如 {@code ROLE_SELLER}
     * @return 权限码列表（字典序），角色不存在时为空列表
     */
    @Select("SELECT DISTINCT p.code FROM role r "
            + "JOIN role_permission rp ON rp.role_id = r.id "
            + "JOIN permission p ON p.id = rp.permission_id "
            + "WHERE r.code = #{roleCode} AND r.deleted = 0 AND r.status = 1 AND p.deleted = 0 "
            + "ORDER BY p.code")
    List<String> selectCodesByRoleCode(@Param("roleCode") String roleCode);

    /**
     * 查询用户跨全部角色的权限码并集。
     *
     * <p>一条 SQL 完成 {@code user_role → role → role_permission → permission} 四表关联，
     * 避免"先查角色再逐个查权限"的 N+1。
     *
     * @param userId 用户 ID
     * @return 去重后的权限码列表（字典序），无权限时为空列表
     */
    @Select("SELECT DISTINCT p.code FROM user_role ur "
            + "JOIN role r ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 1 "
            + "JOIN role_permission rp ON rp.role_id = r.id "
            + "JOIN permission p ON p.id = rp.permission_id AND p.deleted = 0 "
            + "WHERE ur.user_id = #{userId} "
            + "ORDER BY p.code")
    List<String> selectCodesByUserId(@Param("userId") Long userId);
}
