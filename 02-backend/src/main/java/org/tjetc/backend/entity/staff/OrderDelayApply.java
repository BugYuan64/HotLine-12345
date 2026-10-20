package org.tjetc.backend.entity.staff;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 延期申请表 order_delay_apply（需求 表22 / API 11.1.4/11.2.6）
 * 延期最多2次，每次≤3个工作日
 */
@Data
@NoArgsConstructor
public class OrderDelayApply {

    private Long id;
    private Long orderId;
    /** 申请人ID（街道办人员） */
    private Long applyUserId;
    private String applyUserName;
    /** 延期原因（必填） */
    private String delayReason;
    /** 预计完成时间 */
    private LocalDateTime expectedTime;
    /** 审批状态：PENDING/APPROVED/REJECTED */
    private String status;
    /** 审批人ID（部门人员） */
    private Long approveUserId;
    private LocalDateTime approveTime;
    private String approveRemark;
    private LocalDateTime createTime;
}
