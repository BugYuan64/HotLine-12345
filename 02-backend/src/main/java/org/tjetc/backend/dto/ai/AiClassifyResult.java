package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * /ai/v1/classify 响应（API 10.2）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiClassifyResult(
        @JsonProperty("primary_category") String primaryCategory,
        @JsonProperty("sub_category") String subCategory,
        Double confidence,
        @JsonProperty("hit_keywords") List<String> hitKeywords,
        @JsonProperty("similar_samples") List<AiSimilarSample> similarSamples,
        @JsonProperty("need_review") Boolean needReview) {
}
