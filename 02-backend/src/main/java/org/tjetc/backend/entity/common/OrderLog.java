package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工单流转日志表 order_log（需求 表6/表11 / API 11.1.5）
 * 【状态机闭环】所有状态变更必须写入本表，禁止绕过
 */
@Data
@NoArgsConstructor
public class OrderLog {

    private Long id;
    private Long orderId;
    /** 操作类型：GENERATE/CONFIRM/UPGRADE/DISPATCH/VERIFY/ASSIGN_STREET/RECEIVE/DISPOSE/RETURN/DELAY/AUDIT/FINAL_AUDIT/ARCHIVE/REOPEN/EVALUATE 等 */
    private String operateType;
    private Long operatorId;
    /** 操作角色：CITIZEN/SYSTEM/ADMIN/STREET/URBAN/PROPERTY/LIVELIHOOD（时间线角色化展示，不暴露姓名） */
    private String operatorRole;
    private String operateContent;
    /** 修改前数据（JSON） */
    private String beforeData;
    /** 修改后数据（JSON） */
    private String afterData;
    private LocalDateTime createTime;
}
