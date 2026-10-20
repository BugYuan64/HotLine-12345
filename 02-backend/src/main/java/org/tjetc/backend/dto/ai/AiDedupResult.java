package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * /ai/v1/dedup 响应（第二阶段去重归并 AI 相似度兜底）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiDedupResult(
        @JsonProperty("matched_order_id") Long matchedOrderId,
        @JsonProperty("similarity") Double similarity,
        @JsonProperty("matched") Boolean matched) {
}