package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.ClassifyKeyword;

import java.util.List;
import java.util.Map;

@Mapper
public interface ClassifyKeywordMapper {

    List<Map<String, Object>> selectPage(@Param("categoryId") Long categoryId,
                                         @Param("keyword") String keyword,
                                         @Param("status") String status,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    long countPage(@Param("categoryId") Long categoryId,
                   @Param("keyword") String keyword,
                   @Param("status") String status);

    ClassifyKeyword selectById(Long id);

    int countByCategoryAndKeyword(@Param("categoryId") Long categoryId, @Param("keyword") String keyword);

    int insert(ClassifyKeyword keyword);

    int update(ClassifyKeyword keyword);

    int delete(Long id);

    /** 知识桥全量同步：关键词 + 一级分类（写 cls:keywords） */
    List<Map<String, Object>> selectAllWithCategory();

    /** 按关键词列表查询启用中的权重（分类效果测试匹配得分计算） */
    List<ClassifyKeyword> selectWeightsByKeywords(@Param("keywords") List<String> keywords);
}