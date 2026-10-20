package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSplitSuggestion(
        @JsonProperty("category") String category,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("text") String text,
        @JsonProperty("clean_content") String cleanContent,
        @JsonProperty("clean_log") String cleanLog) {
}
