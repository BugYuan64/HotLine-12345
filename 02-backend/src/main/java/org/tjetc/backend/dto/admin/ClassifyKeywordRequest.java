package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 分类关键词维护（API 7.1），字段名与接口文档完全一致
 */
public record ClassifyKeywordRequest(
        @NotNull(message = "分类ID不能为空")
        @JsonProperty("category_id")
        Long categoryId,

        @NotBlank(message = "关键词不能为空")
        @Size(max = 100, message = "关键词长度不能超过100字符")
        String keyword,

        /** 权重，默认 1.0（API 7.1） */
        Double weight,

        /** 状态 ENABLED/DISABLED（编辑时使用） */
        @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "状态必须为ENABLED/DISABLED之一")
        String status) {
}
