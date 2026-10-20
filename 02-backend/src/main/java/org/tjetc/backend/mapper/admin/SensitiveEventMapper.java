package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.SensitiveEvent;

import java.util.List;

@Mapper
public interface SensitiveEventMapper {

    int insert(SensitiveEvent event);

    List<SensitiveEvent> selectPage(@Param("riskLevel") String riskLevel,
                                    @Param("handleStatus") String handleStatus,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    long countPage(@Param("riskLevel") String riskLevel,
                   @Param("handleStatus") String handleStatus);

    /** 按编号前缀统计（SE编号生成用，与 appeal_parent 编号生成同风格） */
    long countByPrefix(@Param("prefix") String prefix);

    int updateHandleStatus(@Param("id") Long id, @Param("handleStatus") String handleStatus);

    /** 市民端"办理进度"：按提诉人手机号分页查询本人敏感事件（编号可精确过滤） */
    List<SensitiveEvent> selectPageByPhone(@Param("citizenPhone") String citizenPhone,
                                           @Param("appealNo") String appealNo,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);

    /** 市民端"办理进度"：按提诉人手机号统计本人敏感事件数 */
    long countPageByPhone(@Param("citizenPhone") String citizenPhone,
                          @Param("appealNo") String appealNo);
}