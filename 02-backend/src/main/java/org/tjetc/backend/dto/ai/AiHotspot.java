package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiHotspot(
        String topic,
        String region,
        String category,
        @JsonProperty("sub_category") String subCategory,
        @JsonProperty("predicted_count") Integer predictedCount,
        Double confidence) {
}
