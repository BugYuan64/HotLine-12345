package org.tjetc.backend.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Admin 重开申请审批（API 11.3.8）：每个工单最多重开1次
 */
public record ReopenApproveRequest(
        @NotBlank(message = "审批操作不能为空")
        @Pattern(regexp = "^(APPROVE|REJECT)$", message = "审批操作必须为APPROVE/REJECT之一")
        String action,

        /** 审批意见 */
        String remark) {
}
