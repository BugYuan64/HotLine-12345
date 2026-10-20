package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 预判结果一键生成预警规则草稿（预警管理融合页）：字段由预判热点回填，生成后需人工确认启用
 */
public record WarningRuleFromPredictionRequest(
        @JsonProperty("category")
        String category,

        @JsonProperty("sub_category")
        String subCategory,

        @JsonProperty("region")
        String region,

        @JsonProperty("predicted_count")
        Integer predictedCount,

        @JsonProperty("period")
        String period,

        @JsonProperty("confidence")
        Double confidence) {
}