package org.tjetc.backend.dto.staff;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 街道办延期申请（API 11.1.4）：延期最多2次，每次≤3个工作日
 */
public record ApplyDelayRequest(
        @NotBlank(message = "延期原因不能为空")
        @JsonProperty("delay_reason")
        String delayReason,

        /** 预计完成时间（ISO 8601） */
        @NotBlank(message = "预计完成时间不能为空")
        @JsonProperty("expected_time")
        String expectedTime) {
}
