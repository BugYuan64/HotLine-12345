package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工单-子诉求归并关联表 work_order_appeal（第二阶段去重归并）：
 * 一个工单可归并多个同源子诉求，is_primary 标记建单首条子诉求（与 work_order.source_appeal_id 一致）
 */
@Data
@NoArgsConstructor
public class WorkOrderAppeal {

    private Long id;
    private Long orderId;
    private Long appealId;
    /** 是否主诉求（建单首条，1=是；归并进来的为0） */
    private Integer isPrimary;
    private LocalDateTime createTime;
}