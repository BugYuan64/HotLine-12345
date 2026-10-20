package org.tjetc.backend.mapper.street;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.SysAuditLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 街道办端审计日志 Mapper（street_audit_log）——【安全红线】仅提供 INSERT/SELECT，禁止 UPDATE/DELETE
 * 三端数据解耦：街道办端账号操作 + 归属本街道的「收到工单」日志写入本表
 */
@Mapper
public interface StreetAuditLogMapper {

    int insert(SysAuditLog log);

    // ---- 街道办审计（管理端视角）：street_audit_log 内街道办账号操作 + 归属街道办的收到工单 ----

    List<Map<String, Object>> selectStreetPage(@Param("logType") String logType,
                                               @Param("module") String module,
                                               @Param("id") Long id,
                                               @Param("startTime") LocalDateTime startTime,
                                               @Param("endTime") LocalDateTime endTime,
                                               @Param("jobNos") List<String> jobNos,
                                               @Param("targetPattern") String targetPattern,
                                               @Param("appealKeyword") String appealKeyword,
                                               @Param("offset") int offset,
                                               @Param("size") int size);

    long countStreetPage(@Param("logType") String logType,
                         @Param("module") String module,
                         @Param("id") Long id,
                         @Param("startTime") LocalDateTime startTime,
                         @Param("endTime") LocalDateTime endTime,
                         @Param("jobNos") List<String> jobNos,
                         @Param("targetPattern") String targetPattern,
                         @Param("appealKeyword") String appealKeyword);

    // ---- 街道办端本端审计视角：本账号操作 + 归属本街道的收到工单 ----

    List<Map<String, Object>> selectOwnPage(@Param("logType") String logType,
                                            @Param("module") String module,
                                            @Param("id") Long id,
                                            @Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime,
                                            @Param("scopeAccount") String scopeAccount,
                                            @Param("targetMatch") String targetMatch,
                                            @Param("appealKeyword") String appealKeyword,
                                            @Param("offset") int offset,
                                            @Param("size") int size);

    long countOwnPage(@Param("logType") String logType,
                      @Param("module") String module,
                      @Param("id") Long id,
                      @Param("startTime") LocalDateTime startTime,
                      @Param("endTime") LocalDateTime endTime,
                      @Param("scopeAccount") String scopeAccount,
                      @Param("targetMatch") String targetMatch,
                      @Param("appealKeyword") String appealKeyword);
}
