package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户角色关联 Mapper（API 5.9 用户角色分配，RBAC 最小权限管理）
 */
@Mapper
public interface SysUserRoleMapper {

    List<Long> selectRoleIdsByUserId(Long userId);

    int deleteByUserId(Long userId);

    int insertBatch(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);
}