package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * /ai/v1/summarize 响应（诉求标题 AI 总结，一般不超过25字）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSummarizeResult(
        String title) {
}
