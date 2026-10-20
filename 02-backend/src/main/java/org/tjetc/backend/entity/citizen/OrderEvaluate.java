package org.tjetc.backend.entity.citizen;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 满意度评价表 order_evaluate（需求 表24 / API 11.4）
 * 办结后7天内评价，超期未评价标记未评价；提交后不可修改
 */
@Data
@NoArgsConstructor
public class OrderEvaluate {

    private Long id;
    private Long orderId;
    /** 诉求编号 */
    private String appealNo;
    /** 评价市民手机号 */
    private String citizenPhone;
    /** 评分（1-5） */
    private Integer score;
    /** 评价标签：VERY_SATISFIED/SATISFIED/BASIC/DISSATISFIED/VERY_DISSATISFIED */
    private String label;
    private String remark;
    /** 是否申请重开（默认0） */
    private Integer isReopenApplied;
    private String reopenReason;
    /** 重开审批状态：PENDING/APPROVED/REJECTED */
    private String reopenStatus;
    private LocalDateTime reopenApproveTime;
    private String reopenApproveRemark;
    /** 评价时间（提交后不可修改） */
    private LocalDateTime evaluateTime;
    private LocalDateTime createTime;
}
