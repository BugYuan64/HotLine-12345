package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 账号审核记录表 sys_account_audit（需求 表1/表4 / API 5.3）
 */
@Data
@NoArgsConstructor
public class SysAccountAudit {

    private Long id;
    /** 申请人ID（sys_user.id） */
    private Long userId;
    /** 审核状态：PENDING/APPROVED/REJECTED */
    private String auditStatus;
    private Long auditUserId;
    private LocalDateTime auditTime;
    private String rejectReason;
    /** 提交时间 */
    private LocalDateTime applyTime;
    private LocalDateTime createTime;
}
