package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * /ai/v1/classify_secondary 响应（API 10.2.1 二级分类）
 * 一级分类已由审核/纠正/一级分类阶段确定，本接口仅返回二级标签与置信度。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSubClassifyResult(
        @JsonProperty("sub_category") String subCategory,
        Double confidence,
        @JsonProperty("need_review") Boolean needReview,
        String method,
        Boolean degraded) {
}
