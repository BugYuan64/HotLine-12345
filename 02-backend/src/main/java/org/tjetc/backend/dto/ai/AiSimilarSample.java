package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * /ai/v1/classify 响应中的相似样本
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSimilarSample(
        @JsonProperty("sample_id") Long sampleId,
        Double similarity) {
}
