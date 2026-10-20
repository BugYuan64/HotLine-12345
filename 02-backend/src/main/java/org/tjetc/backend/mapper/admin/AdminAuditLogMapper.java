package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.SysAuditLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端审计日志 Mapper（admin_audit_log）——【安全红线】仅提供 INSERT/SELECT，禁止 UPDATE/DELETE
 * 三端数据解耦：管理端操作 / AI 生成 / 系统日志写入本表；部门端/街道办端日志见 Dept/StreetAuditLogMapper
 */
@Mapper
public interface AdminAuditLogMapper {

    int insert(SysAuditLog log);

    /**
     * 按模块统计操作审计数量（分类准确率统计用，仅 SELECT）
     */
    long countOperationByModule(@Param("module") String module, @Param("start") LocalDateTime start);

    // ---- 管理端系统审计查询（API 8.6，仅 SELECT，日志不可删除/修改） ----

    List<Map<String, Object>> selectPage(@Param("logType") String logType,
                                         @Param("operator") String operator,
                                         @Param("module") String module,
                                         @Param("id") Long id,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime,
                                         @Param("excludeModule") String excludeModule,
                                         @Param("appealKeyword") String appealKeyword,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    long countPage(@Param("logType") String logType,
                   @Param("operator") String operator,
                   @Param("module") String module,
                   @Param("id") Long id,
                   @Param("startTime") LocalDateTime startTime,
                   @Param("endTime") LocalDateTime endTime,
                   @Param("excludeModule") String excludeModule,
                   @Param("appealKeyword") String appealKeyword);
}
