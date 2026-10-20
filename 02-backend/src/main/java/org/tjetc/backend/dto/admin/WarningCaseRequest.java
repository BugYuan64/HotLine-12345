package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 优秀处置案例维护（预警建议 RAG 检索来源）入参
 */
public record WarningCaseRequest(
        /** 案例编号（空则由后端生成 WC+日期+序号） */
        @JsonProperty("case_no")
        String caseNo,

        @NotBlank(message = "问题标题不能为空")
        String title,

        /** 一级分类（URBAN/PROPERTY/LIVELIHOOD/...） */
        String category,

        /** 二级分类标签 */
        @JsonProperty("sub_category")
        String subCategory,

        /** 所属辖区 */
        String region,

        @NotBlank(message = "诉求描述不能为空")
        String content,

        @NotBlank(message = "处置方案不能为空")
        @JsonProperty("measure_plan")
        String measurePlan,

        /** 处置效果/结果 */
        @JsonProperty("measure_result")
        String measureResult,

        /** 状态 ENABLED/DISABLED */
        @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "状态必须为ENABLED/DISABLED之一")
        String status) {
}