package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 管理员新增工作人员账号（API 5.2 POST）：密码 bcrypt 加密存储，新增账号默认审核通过
 */
public record UserCreateRequest(
        @NotBlank(message = "姓名不能为空")
        @Size(max = 50, message = "姓名不能超过50字")
        String name,

        @NotBlank(message = "工号不能为空")
        @Pattern(regexp = "^[A-Za-z0-9]{6,20}$", message = "工号仅支持字母与数字组合，长度限制6-20位")
        @JsonProperty("job_no")
        String jobNo,

        @Size(max = 100, message = "所属单位不能超过100字")
        String company,

        @NotBlank(message = "归属类型不能为空")
        @Pattern(regexp = "^(STREET|URBAN|TRAFFIC|MARKET|ENVIRONMENT|HOUSING|LABOR|PUBLIC|HEALTH|PROPERTY|LIVELIHOOD)$",
                message = "归属类型不合法")
        @JsonProperty("user_type")
        String userType,

        @Size(max = 100, message = "辖区/部门不能超过100字")
        String region,

        /** 区划编码（部门账号必填，区县级 sys_region.region_code；街道办/遗留类型不填） */
        @Size(max = 20, message = "区划编码不能超过20位")
        @JsonProperty("region_code")
        String regionCode,

        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "登录密码不能为空")
        @Pattern(regexp = "^(?=.*\\d)(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).{8,}$",
                message = "密码强度不足：密码长度不得少于8位，必须同时包含数字、英文字母和特殊字符")
        String password,

        @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "账号状态必须为ENABLED/DISABLED之一")
        String status,

        /** 角色 ID 列表（API 5.9 角色分配，可选） */
        @JsonProperty("role_ids")
        List<Long> roleIds) {
}