package com.ecommerce.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.user.entity.UserRole;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户-角色关联数据访问。
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    /**
     * 幂等绑定用户与角色。
     *
     * <p>使用 {@code INSERT IGNORE} 而非"先 SELECT 再 INSERT"：后者在并发注册
     * （同一用户被两个请求同时补角色）时会撞上唯一键 {@code uk_user_role} 抛异常。
     * 交给数据库的唯一约束去处理并发，是这里最省心也最正确的做法。
     *
     * @param userId 用户 ID
     * @param roleId 角色 ID
     * @return 实际插入行数：1 表示新绑定，0 表示已存在
     */
    @Insert("INSERT IGNORE INTO user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insertIgnore(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
