package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * 分类规则配置（API 7.2），字段名与接口文档完全一致
 */
public record ClassifyRuleRequest(
        @NotNull(message = "分类ID不能为空")
        @JsonProperty("category_id")
        Long categoryId,

        /** 关键词命中数量阈值 */
        @JsonProperty("hit_count_threshold")
        Integer hitCountThreshold,

        /** 权重求和阈值 */
        @JsonProperty("weight_sum_threshold")
        Double weightSumThreshold) {
}
