package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 系统审计日志表 sys_audit_log（需求 19(4)/20 / API 8.6）
 * 【安全红线】仅 INSERT，禁止 UPDATE/DELETE，永久留存
 */
@Data
@NoArgsConstructor
public class SysAuditLog {

    private Long id;
    /** 日志类型：LOGIN/OPERATION/ERROR/AI_GENERATION */
    private String logType;
    private String operator;
    private String module;
    /** 内容类型（AI生成：CLASSIFY/DETECT/SUGGEST/PREDICT/WARNING/CHAT 等） */
    private String contentType;
    private String summary;
    private BigDecimal confidence;
    /** 决策依据（AI生成：命中关键词/相似样本等） */
    private String decisionBasis;
    /** 模型标识（AI生成） */
    private String model;
    /** 日志归属端（部门代号/STREET）：「收到工单」类日志操作人是发起方，按归属端过滤展示 */
    private String targetType;
    private LocalDateTime createTime;
}
