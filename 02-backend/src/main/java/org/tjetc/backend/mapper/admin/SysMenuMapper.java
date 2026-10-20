package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 菜单权限 Mapper（API 5.10 角色权限配置）
 */
@Mapper
public interface SysMenuMapper {

    List<Map<String, Object>> selectAll();

    List<Long> selectMenuIdsByRoleId(Long roleId);

    int deleteByRoleId(Long roleId);

    int insertBatch(@Param("roleId") Long roleId, @Param("menuIds") List<Long> menuIds);
}