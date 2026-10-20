package org.tjetc.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 账号审核驳回（API 5.5）：驳回原因必填，≤500字，用户登录时展示驳回原因
 */
public record AccountAuditRejectRequest(
        @NotBlank(message = "驳回原因不能为空")
        @Size(max = 500, message = "驳回原因不能超过500字")
        @JsonProperty("reject_reason")
        String rejectReason) {
}