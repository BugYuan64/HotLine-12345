package org.tjetc.backend.dto.staff;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 部门批量审核（API 11.2.4）：批量通过限同类处置标签，敏感/紧急工单不支持批量
 */
public record BatchAuditRequest(
        @NotEmpty(message = "工单ID列表不能为空")
        @JsonProperty("order_ids")
        List<Long> orderIds,

        @Pattern(regexp = "^(APPROVE|REJECT)$", message = "操作类型必须为APPROVE/REJECT之一")
        String action,

        /** 批量退回原因（action=REJECT 时必填） */
        @JsonProperty("reject_reason")
        String rejectReason) {
}
