package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * /ai/v1/warning/cases/search 响应中的单条处置案例（API 10.12）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiCaseSearchItem(
        @JsonProperty("case_no") String caseNo,
        String title,
        Double similarity,
        @JsonProperty("measure_plan") String measurePlan,
        @JsonProperty("measure_result") String measureResult,
        String category,
        String region) {
}