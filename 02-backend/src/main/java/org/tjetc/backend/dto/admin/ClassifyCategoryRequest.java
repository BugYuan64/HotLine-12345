package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 新增二级分类标签（需求 10.3(1) 分类体系管理，API 文档未单独定义，按需求补充）
 * 一级分类不再硬编码枚举，由 Service 层按 classify_primary 表动态校验
 */
public record ClassifyCategoryRequest(
        @NotBlank(message = "一级分类不能为空")
        @JsonProperty("primary_category")
        String primaryCategory,

        @NotBlank(message = "二级标签不能为空")
        @JsonProperty("sub_label")
        String subLabel) {
}
