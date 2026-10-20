package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * /ai/v1/warning/measure 响应（API 10.10）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiWarningMeasureResult(
        @JsonProperty("measures") List<AiWarningMeasureItem> measures) {
}
