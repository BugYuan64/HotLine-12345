package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.tjetc.backend.entity.admin.WarningRule;

import java.util.List;

@Mapper
public interface WarningRuleMapper {

    List<WarningRule> selectAll();

    /** 启用中的规则（定时扫描触发用） */
    List<WarningRule> selectEnabled();

    WarningRule selectById(Long id);

    int insert(WarningRule rule);

    int update(WarningRule rule);

    int delete(Long id);
}