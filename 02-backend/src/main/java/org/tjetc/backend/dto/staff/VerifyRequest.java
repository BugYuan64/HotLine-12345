package org.tjetc.backend.dto.staff;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 部门筛选核验（API 9.2）：CONFIRM 确认归属 / RETURN 退回重分
 */
public record VerifyRequest(
        @NotBlank(message = "操作类型不能为空")
        @Pattern(regexp = "^(CONFIRM|RETURN)$", message = "操作类型必须为CONFIRM/RETURN之一")
        String action,

        /** 退回原因（action=RETURN 时必填） */
        @JsonProperty("return_reason")
        String returnReason) {
}
