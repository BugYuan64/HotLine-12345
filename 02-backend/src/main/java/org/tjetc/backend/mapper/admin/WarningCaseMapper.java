package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.WarningCase;

import java.util.List;
import java.util.Map;

@Mapper
public interface WarningCaseMapper {

    List<Map<String, Object>> selectPage(@Param("keyword") String keyword,
                                         @Param("category") String category,
                                         @Param("region") String region,
                                         @Param("status") String status,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    long countPage(@Param("keyword") String keyword,
                   @Param("category") String category,
                   @Param("region") String region,
                   @Param("status") String status);

    WarningCase selectById(Long id);

    int insert(WarningCase c);

    int update(WarningCase c);

    int delete(Long id);

    int countByPrefix(String prefix);
}