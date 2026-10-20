package org.tjetc.backend.dto.staff;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 部门延期审批（API 11.2.6）：通过后自动更新工单截止时间（deadline）
 */
public record DelayApproveRequest(
        @NotBlank(message = "审批操作不能为空")
        @Pattern(regexp = "^(APPROVE|REJECT)$", message = "审批操作必须为APPROVE/REJECT之一")
        String action,

        /** 审批备注 */
        String remark) {
}
