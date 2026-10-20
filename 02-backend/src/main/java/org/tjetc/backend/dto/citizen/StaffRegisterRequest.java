package org.tjetc.backend.dto.citizen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 工作人员自助注册（API 3.1）：注册校验采用 QQ 邮箱验证码（替换原短信验证码），字段名与接口文档一致
 */
public record StaffRegisterRequest(
        @NotBlank(message = "姓名不能为空")
        String name,

        @NotBlank(message = "工号不能为空")
        @Pattern(regexp = "^[A-Za-z0-9]{6,20}$", message = "工号仅支持字母与数字组合，长度限制6-20位")
        @JsonProperty("job_no")
        String jobNo,

        @NotBlank(message = "所属单位不能为空")
        String company,

        @NotBlank(message = "归属类型不能为空")
        @Pattern(regexp = "^(STREET|URBAN|PROPERTY|LIVELIHOOD)$", message = "归属类型必须为STREET/URBAN/PROPERTY/LIVELIHOOD之一")
        @JsonProperty("user_type")
        String userType,

        @NotBlank(message = "对应辖区/部门不能为空")
        String region,

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "邮箱不能为空")
        @Pattern(regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", message = "邮箱格式不正确")
        String email,

        @NotBlank(message = "邮箱验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码为6位数字")
        @JsonProperty("email_code")
        String emailCode,

        @NotBlank(message = "登录密码不能为空")
        @Pattern(regexp = "^(?=.*\\d)(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).{8,}$",
                message = "密码强度不足：密码长度不得少于8位，必须同时包含数字、英文字母和特殊字符")
        String password,

        @NotBlank(message = "确认密码不能为空")
        @JsonProperty("confirm_password")
        String confirmPassword) {
}
