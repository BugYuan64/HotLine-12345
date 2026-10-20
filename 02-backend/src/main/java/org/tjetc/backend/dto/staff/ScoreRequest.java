package org.tjetc.backend.dto.staff;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 处置质量评分（API 11.2.5）：1-5星，分维度评分仅部门内部可见
 */
public record ScoreRequest(
        @NotNull(message = "评分不能为空")
        @Min(value = 1, message = "评分范围1-5星")
        @Max(value = 5, message = "评分范围1-5星")
        Integer score,

        /** 分维度评分 {时效性, 效果, 完整性}（选填，留痕用） */
        Map<String, Object> dimensions) {
}
