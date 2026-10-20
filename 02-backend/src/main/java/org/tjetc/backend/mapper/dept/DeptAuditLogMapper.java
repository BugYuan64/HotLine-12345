package org.tjetc.backend.mapper.dept;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.SysAuditLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 部门端审计日志 Mapper（dept_audit_log）——【安全红线】仅提供 INSERT/SELECT，禁止 UPDATE/DELETE
 * 三端数据解耦：部门端账号操作 + 归属本部门的「收到工单」日志写入本表
 */
@Mapper
public interface DeptAuditLogMapper {

    int insert(SysAuditLog log);

    // ---- 部门审计（管理端视角）：dept_audit_log 内部门账号操作 + 归属部门的收到工单 ----

    List<Map<String, Object>> selectDeptPage(@Param("logType") String logType,
                                             @Param("module") String module,
                                             @Param("id") Long id,
                                             @Param("dept") String dept,
                                             @Param("countyCode") String countyCode,
                                             @Param("jobNos") List<String> jobNos,
                                             @Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime,
                                             @Param("appealKeyword") String appealKeyword,
                                             @Param("offset") int offset,
                                             @Param("size") int size);

    long countDeptPage(@Param("logType") String logType,
                       @Param("module") String module,
                       @Param("id") Long id,
                       @Param("dept") String dept,
                       @Param("countyCode") String countyCode,
                       @Param("jobNos") List<String> jobNos,
                       @Param("startTime") LocalDateTime startTime,
                       @Param("endTime") LocalDateTime endTime,
                       @Param("appealKeyword") String appealKeyword);

    // ---- 部门端本端审计视角：本账号操作 + 归属本部门的收到工单 ----

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
