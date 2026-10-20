package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 管理员手动分派/转派（API 6.10）；驳回工单重新分类分派复用本接口（category/sub_category 可选）
 */
public record OrderDispatchRequest(
        @NotBlank(message = "目标部门不能为空")
        String department,

        /** 承办街道办（部门核验后分派时填） */
        @JsonProperty("street_office")
        String streetOffice,

        /** 重新分类：一级分类代号（URBAN 等，可选） */
        String category,

        /** 重新分类：二级分类标签（可选） */
        @JsonProperty("sub_category")
        String subCategory,

        /** 区县编码（sys_region.region_code，level=COUNTY；分派弹窗选择的区县，工单缺区县时回填） */
        @JsonProperty("county_code")
        String countyCode,

        /** 转派说明 */
        String remark) {
}
