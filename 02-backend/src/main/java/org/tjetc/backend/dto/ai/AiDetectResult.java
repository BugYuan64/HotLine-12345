package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * /ai/v1/detect 响应（API 10.1）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiDetectResult(
        List<AiDetectCategory> categories,
        @JsonProperty("multi_category") Boolean multiCategory,
        @JsonProperty("split_suggestions") List<AiSplitSuggestion> splitSuggestions,
        @JsonProperty("method") String method) {
}
