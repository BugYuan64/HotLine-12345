package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理员编辑工作人员账号（API 5.2 PUT）：修改基础信息，也可单独重置登录密码
 * 字段均为选填，仅更新传入的非空字段
 */
public record UserUpdateRequest(
        @Size(max = 50, message = "姓名不能超过50字")
        String name,

        @Size(max = 100, message = "所属单位不能超过100字")
        String company,

        @Pattern(regexp = "^(STREET|URBAN|TRAFFIC|MARKET|ENVIRONMENT|HOUSING|LABOR|PUBLIC|HEALTH|PROPERTY|LIVELIHOOD)$",
                message = "归属类型不合法")
        @JsonProperty("user_type")
        String userType,

        @Size(max = 100, message = "辖区/部门不能超过100字")
        String region,

        /** 区划编码（部门账号编辑时选填，区县级 sys_region.region_code） */
        @Size(max = 20, message = "区划编码不能超过20位")
        @JsonProperty("region_code")
        String regionCode,

        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        /** 重置密码（选填，传入时重新加密覆盖） */
        @Pattern(regexp = "^(?=.*\\d)(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).{8,}$",
                message = "密码强度不足：密码长度不得少于8位，必须同时包含数字、英文字母和特殊字符")
        String password) {
}