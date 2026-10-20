package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.RagSample;

import java.util.List;
import java.util.Map;

@Mapper
public interface RagSampleMapper {

    List<Map<String, Object>> selectPage(@Param("categoryId") Long categoryId,
                                         @Param("source") String source,
                                         @Param("qualityLevel") String qualityLevel,
                                         @Param("status") String status,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    long countPage(@Param("categoryId") Long categoryId,
                   @Param("source") String source,
                   @Param("qualityLevel") String qualityLevel,
                   @Param("status") String status);

    RagSample selectById(Long id);

    int insert(RagSample sample);

    int update(RagSample sample);

    int delete(Long id);

    /** 导出全量样本（API 7.6） */
    List<RagSample> selectAll();
}