package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.tjetc.backend.entity.admin.SysRole;

import java.util.List;

@Mapper
public interface SysRoleMapper {

    List<SysRole> selectByUserId(Long userId);

    /** 查询全部角色（API 5.8 角色列表） */
    List<SysRole> selectAll();
}