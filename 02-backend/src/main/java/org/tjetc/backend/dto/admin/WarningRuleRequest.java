package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 预警规则维护（API 8.1），字段名与接口文档完全一致
 */
public record WarningRuleRequest(
        @NotBlank(message = "规则名称不能为空")
        @JsonProperty("rule_name")
        String ruleName,

        /** 时间维度：DAY/3_DAY/7_DAY（API 8.1） */
        @NotBlank(message = "时间维度不能为空")
        @Pattern(regexp = "^(DAY|3_DAY|7_DAY)$", message = "时间维度必须为DAY/3_DAY/7_DAY之一")
        @JsonProperty("time_dimension")
        String timeDimension,

        /** 一级分类（NULL=全部分类，动态来自 classify_primary 表） */
        String category,

        /** 二级分类标签（NULL=该一级下全部分类） */
        @JsonProperty("sub_category")
        String subCategory,

        /** 辖区（NULL=全部辖区） */
        String region,

        @NotNull(message = "累计关注度阈值不能为空")
        @Min(value = 1, message = "累计关注度阈值不能小于1")
        @Max(value = 999999, message = "累计关注度阈值过大")
        @JsonProperty("count_threshold")
        Integer countThreshold,

        /** 预警等级：GENERAL/SEVERE/URGENT（API 14.1） */
        @NotBlank(message = "预警等级不能为空")
        @Pattern(regexp = "^(GENERAL|SEVERE|URGENT)$", message = "预警等级必须为GENERAL/SEVERE/URGENT之一")
        @JsonProperty("warning_level")
        String warningLevel,

        /** 推送对象（旧格式兼容，归属类型列表）：新数据请用 push_counties/push_categories 双限制 */
        @JsonProperty("push_targets")
        List<String> pushTargets,

        /** 辖区限制（区县code列表，空=全部区县） */
        @JsonProperty("push_counties")
        List<String> pushCounties,

        /** 部门类别限制（分类code列表，空=全部部门类别） */
        @JsonProperty("push_categories")
        List<String> pushCategories,

        /** 状态 ENABLED/DISABLED */
        @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "状态必须为ENABLED/DISABLED之一")
        String status) {
}
