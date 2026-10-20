package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * /ai/v1/sensitive 响应（API 10.7）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSensitiveResult(
        @JsonProperty("risk_type") String riskType,
        @JsonProperty("risk_level") String riskLevel,
        String advice) {
}
