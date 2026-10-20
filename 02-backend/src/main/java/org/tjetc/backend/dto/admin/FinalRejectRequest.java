package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Admin 终审退回（API 11.3.4）：退回目标 dept/street，退回最多1次
 */
public record FinalRejectRequest(
        @NotBlank(message = "退回目标不能为空")
        @Pattern(regexp = "^(dept|street)$", message = "退回目标必须为dept/street之一")
        @JsonProperty("reject_target")
        String rejectTarget,

        @NotBlank(message = "退回原因不能为空")
        @Size(max = 500, message = "退回原因不能超过500字")
        @JsonProperty("reject_reason")
        String rejectReason) {
}
