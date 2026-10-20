package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * /ai/v1/warning/measure 响应中的单条措施（API 10.10）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiWarningMeasureItem(
        String measure,
        String reason) {
}
