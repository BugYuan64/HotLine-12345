package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.tjetc.backend.entity.admin.ClassifyRule;

import java.util.List;
import java.util.Map;

@Mapper
public interface ClassifyRuleMapper {

    /** 规则列表（含一级分类，供知识桥 cls:rules 同步与前端展示） */
    List<Map<String, Object>> selectAllWithCategory();

    ClassifyRule selectByCategoryId(Long categoryId);

    int insert(ClassifyRule rule);

    int update(ClassifyRule rule);
}