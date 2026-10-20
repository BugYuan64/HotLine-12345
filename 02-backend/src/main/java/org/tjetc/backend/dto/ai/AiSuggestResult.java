package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * /ai/v1/suggest 响应（API 10.4）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSuggestResult(
        @JsonProperty("suggested_title") String suggestedTitle,
        String completion,
        @JsonProperty("missing_elements") List<String> missingElements) {
}
