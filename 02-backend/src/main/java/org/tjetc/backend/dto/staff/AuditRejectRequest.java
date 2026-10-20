package org.tjetc.backend.dto.staff;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 部门审核退回（API 11.2.3）：退回原因必填，≤500字
 */
public record AuditRejectRequest(
        @NotBlank(message = "退回原因不能为空")
        @Size(max = 500, message = "退回原因不能超过500字")
        @JsonProperty("reject_reason")
        String rejectReason) {
}
