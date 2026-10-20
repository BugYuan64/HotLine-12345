package org.tjetc.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 编辑工单基础信息（API 6.6）：诉求标题/内容/辖区/一级分类/二级分类/分派部门/承办街道办，均为选填，修改留痕。
 * department 为「区县+部门名」全称（如「海港区城管部门」）：详情弹窗下拉选择，解析后同步更新区县与一级分类。
 */
public record OrderUpdateRequest(
        String title,
        String content,
        String region,
        String category,
        @JsonProperty("sub_category") String subCategory,
        @JsonProperty("department") String department,
        @JsonProperty("street_office") String streetOffice) {
}
