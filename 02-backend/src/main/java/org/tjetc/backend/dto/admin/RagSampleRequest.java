package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * RAG 知识库样本维护（API 7.3），字段名与接口文档完全一致
 */
public record RagSampleRequest(
        @NotNull(message = "分类ID不能为空")
        @JsonProperty("category_id")
        Long categoryId,

        @NotBlank(message = "样本内容不能为空")
        String content,

        /** 来源：MANUAL_REVIEW/EXPERT/SATISFIED/CORRECTED（API 7.3） */
        @NotBlank(message = "样本来源不能为空")
        @Pattern(regexp = "^(MANUAL_REVIEW|EXPERT|SATISFIED|CORRECTED)$",
                message = "样本来源必须为MANUAL_REVIEW/EXPERT/SATISFIED/CORRECTED之一")
        String source,

        /** 质量等级 P0/P1/P2/P3（API 14.1） */
        @NotBlank(message = "质量等级不能为空")
        @Pattern(regexp = "^(P0|P1|P2|P3)$", message = "质量等级必须为P0/P1/P2/P3之一")
        @JsonProperty("quality_level")
        String qualityLevel,

        /** 原AI错误类别（P3纠错样本标记） */
        @JsonProperty("original_error_category")
        String originalErrorCategory,

        /** 状态 ENABLED/DISABLED */
        @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "状态必须为ENABLED/DISABLED之一")
        String status) {
}
