package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 预警记录审核内容编辑（方案B：AI 内容审核中可人工修订总结与应对措施，再决定发送）
 */
public record WarningRecordReviewRequest(
        @JsonProperty("ai_summary")
        String aiSummary,

        @JsonProperty("measure")
        String measure) {
}