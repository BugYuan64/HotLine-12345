package org.tjetc.backend.dto.citizen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 市民满意度评价（API 11.4.2）：街道办处置完成后7天内评价，提交后不可修改
 * phone 允许为空：登录态由后端以登录账号手机号自动核验（verifyAppeal 回退），未登录时 service 层强制校验必填
 */
public record EvaluateRequest(
        String phone,

        /** 子诉求编号（AS开头，可选）：拆分后对单个子诉求的工单评价；为空按父诉求定位（兼容旧单） */
        @JsonProperty("child_appeal_id")
        String childAppealId,

        @NotNull(message = "评分不能为空")
        @Min(value = 1, message = "评分范围1-5")
        @Max(value = 5, message = "评分范围1-5")
        Integer score,

        /** 评价标签：VERY_SATISFIED/SATISFIED/BASIC/DISSATISFIED/VERY_DISSATISFIED（API 14.1） */
        @NotBlank(message = "评价标签不能为空")
        @Pattern(regexp = "^(VERY_SATISFIED|SATISFIED|BASIC|DISSATISFIED|VERY_DISSATISFIED)$",
                message = "评价标签不合法")
        String label,

        /** 文字说明（≤200字） */
        @Size(max = 200, message = "评价说明不能超过200字")
        String remark) {
}
