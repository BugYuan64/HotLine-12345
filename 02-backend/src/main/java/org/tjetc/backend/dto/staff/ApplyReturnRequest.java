package org.tjetc.backend.dto.staff;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 街道办退回申请（API 11.1.3）：退回原因必填，≤500字
 */
public record ApplyReturnRequest(
        @NotBlank(message = "退回原因不能为空")
        @Size(max = 500, message = "退回原因不能超过500字")
        @JsonProperty("return_reason")
        String returnReason) {
}
