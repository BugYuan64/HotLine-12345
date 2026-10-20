package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * /ai/v1/warning/cases/search 响应（API 10.12）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiCaseSearchResult(
        @JsonProperty("cases") List<AiCaseSearchItem> cases) {
}