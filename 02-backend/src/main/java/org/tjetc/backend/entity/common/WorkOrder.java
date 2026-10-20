package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工单表 work_order（需求 表6/表19/表21/表23 / API 6.2；含闭环全部字段）
 * 状态机流转（API 14.1 order status）：
 * PENDING_DISPATCH → PENDING_DEPT_PROCESS → PENDING_DEPT_ASSIGN → PENDING_STREET_PROCESS
 * → STREET_DONE → CLOSED（已办结；管理端终审确认后 is_archived=1 转终审归档）/ EVALUATED
 * 异常分支：RETURNED（部门退回重分）/ WITHDRAWN（市民撤回），超期仅标记不改变状态
 */
@Data
@NoArgsConstructor
public class WorkOrder {

    private Long id;
    /** 工单编号（WO+日期+序号，全局唯一） */
    private String orderNo;
    /** 来源诉求ID */
    private Long sourceAppealId;
    private String title;
    private String content;
    /** 一级分类：URBAN/PROPERTY/LIVELIHOOD */
    private String category;
    /** 二级分类标签 */
    private String subCategory;
    /** 工单等级：一级/二级/三级 */
    private String level;
    /** 关注度（默认1，同源合并累加） */
    private Integer attentionCount;
    /** AI置信度（0~1） */
    private BigDecimal confidence;
    /** 命中关键词（逗号分隔） */
    private String hitKeywords;
    /** 低置信度待人工复核标记 */
    private Integer needReview;
    /** 所属辖区 */
    private String region;
    /** 区县编码（sys_region.region_code，level=COUNTY） */
    private String countyCode;
    /** 区县名称 */
    private String countyName;
    /** 分派部门（城管部门/物业部门/民生部门） */
    private String department;
    /** 承办街道办 */
    private String streetOffice;
    /** 工单到达本部门时间（管理端分派时写入，部门端未读红点基准） */
    private LocalDateTime deptReceiveTime;
    /** 工单到达街道办时间（部门分派时写入，街道办端未读红点基准） */
    private LocalDateTime streetReceiveTime;
    /** 街道/镇/乡编码（用于匹配街道办账号） */
    private String streetCode;
    /** 街道/镇/乡名称 */
    private String streetName;
    /** 详细地址 */
    private String addressDetail;
    /** 是否宽泛诉求（区县级，管理员端手动派发街道办） */
    private Integer isBroad;
    /** 流转状态（API 14.1 order status） */
    private String status;
    /** 同源工单ID（供重复研判与关注度统计） */
    private Long sameSourceOrderId;
    /** 事件类型（去重归并键，由子诉求event_type带入） */
    private String eventType;
    // ---- 街道办处置字段（需求 表19）----
    private String disposePlan;
    private String disposeResult;
    /** 处置标签：RESOLVED/PART_RESOLVED/UNRESOLVED/TRANSFERRED */
    private String disposeTag;
    private LocalDateTime disposeTime;
    private Long disposeUserId;
    private String disposeUserName;
    /** 处理截止时间（工单等级自动计算：一级/二级2个工作日，三级5个工作日） */
    private LocalDateTime deadline;
    /** 是否超期（默认0，超时自动标记，状态不变） */
    private Integer isOverdue;
    /** 已延期次数（默认0，上限2次） */
    private Integer delayCount;
    /** 街道办退回部门次数（默认0） */
    private Integer streetReturnCount;
    /** 部门退回街道办次数（默认0，上限2次，第3次强制升级admin） */
    private Integer deptReturnCount;
    // ---- 部门审核字段（需求 表21）----
    private String deptAuditStatus;
    private LocalDateTime deptAuditTime;
    private Long deptAuditUserId;
    private String deptAuditUserName;
    private String deptAuditRemark;
    /** 部门评分（1-5星） */
    private Integer deptScore;
    private LocalDateTime deptScoreTime;
    // ---- 终审与归档字段（需求 表23）----
    private String finalAuditStatus;
    private LocalDateTime finalAuditTime;
    private Long finalAuditUserId;
    private String finalAuditUserName;
    private String finalAuditRemark;
    /** 终审退回目标：dept/street */
    private String finalRejectTarget;
    private LocalDateTime archiveTime;
    /** 归档类型：MANUAL/AUTO */
    private String archiveType;
    /** 工单是否完成：1=已办结(CLOSED/EVALUATED) 0=未办结(驳回归档等) */
    private Integer isCompleted;
    /** 是否已归档（归档后只读，不可修改） */
    private Integer isArchived;
    /** 重开次数（默认0，上限1） */
    private Integer reopenCount;
    /** 办结时间 */
    private LocalDateTime closeTime;
    /** 逻辑删除：0正常/1已删除 */
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
