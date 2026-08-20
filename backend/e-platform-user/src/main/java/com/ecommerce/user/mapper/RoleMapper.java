package com.ecommerce.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.user.entity.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色数据访问。
 *
 * <p><b>为何手写 SQL 而非全用 MyBatis-Plus 的 Wrapper</b>：角色查询天然是
 * {@code user_role JOIN role} 的两表关联，用 Wrapper 需要先查 roleId 列表再查 role 表
 * （两次往返 + 一次 N+1 隐患）。这里一条 JOIN 直接拿角色码，更快也更直白。
 *
 * <p><b>关于 {@code deleted = 0}</b>：{@link com.baomidou.mybatisplus.annotation.TableLogic}
 * 只对 MyBatis-Plus 自动生成的 SQL 生效，对 {@code @Select} 手写 SQL <b>不生效</b>。
 * 因此下面每条语句都显式带上了逻辑删除条件，漏写会把已删角色也查出来。
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 查询用户持有的全部角色码。
     *
     * <p>按角色码字典序返回，与网关 {@code AuthPrincipal} 内部的 {@code TreeSet}
     * 排序保持一致，便于日志比对与 HMAC 签名原文拼接。
     *
     * @param userId 用户 ID
     * @return 角色码列表（如 {@code [ROLE_ADMIN, ROLE_BUYER]}），无角色时为空列表
     */
    @Select("SELECT r.code FROM user_role ur "
            + "JOIN role r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId} AND r.deleted = 0 AND r.status = 1 "
            + "ORDER BY r.code")
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * 按角色码查主键。
     *
     * @param code 角色码，如 {@code ROLE_BUYER}
     * @return 角色主键；角色不存在或已禁用时返回 null
     */
    @Select("SELECT id FROM role WHERE code = #{code} AND deleted = 0 AND status = 1 LIMIT 1")
    Long selectIdByCode(@Param("code") String code);

    /**
     * 查询全部启用中的角色码，供启动时预热 Redis 权限缓存。
     *
     * @return 角色码列表
     */
    @Select("SELECT code FROM role WHERE deleted = 0 AND status = 1 ORDER BY code")
    List<String> selectAllActiveCodes();
}
