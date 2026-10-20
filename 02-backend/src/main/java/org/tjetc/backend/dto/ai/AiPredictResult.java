package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * /ai/v1/predict 响应（API 10.6）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiPredictResult(List<AiTrendPoint> trend, List<AiHotspot> hotspots) {
}
