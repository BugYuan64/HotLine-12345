package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 一级分类新增/编辑（需求 10.3(1) 分类体系维护）
 */
public record ClassifyPrimaryRequest(
        @NotBlank(message = "分类代码不能为空")
        String code,

        @NotBlank(message = "分类名称不能为空")
        String name,

        @NotBlank(message = "分派部门不能为空")
        String department,

        @JsonProperty("sort_order")
        Integer sortOrder,

        String status) {
}
