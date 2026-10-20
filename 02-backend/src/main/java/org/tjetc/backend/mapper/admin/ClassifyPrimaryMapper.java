package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.ClassifyPrimary;

import java.util.List;

/**
 * 一级分类 Mapper（需求 10.3(1) 分类体系维护）
 */
@Mapper
public interface ClassifyPrimaryMapper {

    List<ClassifyPrimary> selectAll();

    List<ClassifyPrimary> selectEnabled();

    ClassifyPrimary selectById(Long id);

    ClassifyPrimary selectByCode(@Param("code") String code);

    int insert(ClassifyPrimary primary);

    int update(ClassifyPrimary primary);

    int delete(@Param("id") Long id);
}
