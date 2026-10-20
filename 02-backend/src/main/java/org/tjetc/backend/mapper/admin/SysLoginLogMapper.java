package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.tjetc.backend.entity.admin.SysLoginLog;

import java.util.List;

@Mapper
public interface SysLoginLogMapper {

    int insert(SysLoginLog log);

    List<SysLoginLog> selectByUserId(Long userId, int offset, int limit);

    long countByUserId(Long userId);
}