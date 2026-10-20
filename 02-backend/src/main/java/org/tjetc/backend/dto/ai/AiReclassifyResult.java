package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * /ai/v1/reclassify 响应（API 10.3）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiReclassifyResult(
        @JsonProperty("task_id") String taskId,
        String status,
        Integer total) {
}
