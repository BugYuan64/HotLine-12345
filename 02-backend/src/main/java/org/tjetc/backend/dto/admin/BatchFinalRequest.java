package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Admin 批量终审（API 11.3.5）：批量通过限同一部门，敏感/紧急工单不支持批量
 */
public record BatchFinalRequest(
        @NotEmpty(message = "工单ID列表不能为空")
        @JsonProperty("order_ids")
        List<Long> orderIds,

        @Pattern(regexp = "^(APPROVE|REJECT)$", message = "操作类型必须为APPROVE/REJECT之一")
        String action,

        /** 批量退回参数（action=REJECT 时） */
        @JsonProperty("reject_target")
        String rejectTarget,

        @JsonProperty("reject_reason")
        String rejectReason) {
}
