package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * /ai/v1/warning/summary 响应（API 10.9）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiWarningSummaryResult(
        String summary,
        Double confidence) {
}
