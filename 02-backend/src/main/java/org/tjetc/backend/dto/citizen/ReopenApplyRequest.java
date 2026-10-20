package org.tjetc.backend.dto.citizen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 市民重开申请（API 11.4.3）：评价为不满意/非常不满意时可申请，每个工单最多1次
 * phone 允许为空：登录态由后端以登录账号手机号自动核验（verifyAppeal 回退），未登录时 service 层强制校验必填
 */
public record ReopenApplyRequest(
        String phone,

        /** 子诉求编号（AS开头，可选）：拆分后对单个子诉求的工单申请重开；为空按父诉求定位 */
        @JsonProperty("child_appeal_id")
        String childAppealId,

        @NotBlank(message = "重开补充说明不能为空")
        @Size(max = 500, message = "重开补充说明不能超过500字")
        @JsonProperty("reopen_reason")
        String reopenReason) {
}
