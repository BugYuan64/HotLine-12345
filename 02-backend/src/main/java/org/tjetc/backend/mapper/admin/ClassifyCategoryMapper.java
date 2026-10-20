package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.ClassifyCategory;

import java.util.List;

@Mapper
public interface ClassifyCategoryMapper {

    List<ClassifyCategory> selectAll();

    ClassifyCategory selectById(Long id);

    ClassifyCategory selectByPrimaryAndLabel(@Param("primaryCategory") String primaryCategory,
                                            @Param("subLabel") String subLabel);

    int insert(ClassifyCategory category);
}