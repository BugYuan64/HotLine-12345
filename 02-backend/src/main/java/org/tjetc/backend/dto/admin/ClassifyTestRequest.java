package org.tjetc.backend.dto.admin;

import jakarta.validation.constraints.NotBlank;

/**
 * 分类效果测试（API 7.4）
 */
public record ClassifyTestRequest(
        @NotBlank(message = "测试诉求文本不能为空")
        String text) {
}
