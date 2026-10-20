package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理员编辑诉求（诉求管理模块）：字段均可选，提交后覆盖更新；手机号以提交值为准（前端回填原值）
 */
public record AppealUpdateRequest(
        @Size(max = 200, message = "诉求标题不能超过200字")
        String title,

        @Pattern(regexp = "^(TITLE_PENDING|TITLE_GENERATING|TITLE_GENERATED|SPLITTING|AUDIT_PENDING|AUDITING|CORRECTING|CLASSIFYING|CHILDREN_GENERATED|PROCESSING|READY|ORDERING|ORDERED|WITHDRAWN)?$",
                message = "诉求状态值不合法")
        String status,

        String content,

        /** 诉求类别（8大类 code 或 OTHER，可选） */
        String category,

        @Size(max = 50, message = "市民姓名不能超过50字")
        @JsonProperty("citizen_name")
        String citizenName,

        @JsonProperty("citizen_phone")
        String citizenPhone,

        @Size(max = 50, message = "区级行政区划不能超过50字")
        String district,

        @Size(max = 50, message = "街道（乡镇）不能超过50字")
        String street,

        @Size(max = 50, message = "社区不能超过50字")
        String community,

        @Size(max = 200, message = "详细地址不能超过200字")
        @JsonProperty("address_detail")
        String addressDetail,

        @JsonProperty("broad_flag")
        Boolean broadFlag) {
}