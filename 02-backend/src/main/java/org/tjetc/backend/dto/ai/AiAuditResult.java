package org.tjetc.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * /ai/v1/audit-category 响应（诉求内容与类别一致性审核）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiAuditResult(
        boolean consistent,
        String reason) {
}
