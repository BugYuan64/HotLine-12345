package org.tjetc.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 账号批量审核（API 5.7）：批量通过/批量驳回，驳回时 reject_reason 必填
 */
public record AccountAuditBatchRequest(
        @NotEmpty(message = "审核记录ID列表不能为空")
        @JsonProperty("audit_ids")
        List<Long> auditIds,

        @Pattern(regexp = "^(APPROVE|REJECT)$", message = "操作类型必须为APPROVE/REJECT之一")
        String action,

        /** 批量驳回原因（action=REJECT 时必填） */
        @JsonProperty("reject_reason")
        String rejectReason) {
}