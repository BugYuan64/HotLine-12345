package org.tjetc.backend.dto.citizen;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 忘记密码（重置登录密码）请求：手机号 + 短信验证码 + 新密码 + 确认密码。
 * 仅对已注册的部门/街道办工作人员生效
 */
public record ResetPasswordRequest(

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "短信验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码为6位数字")
        String smsCode,

        @NotBlank(message = "新密码不能为空")
        @Pattern(regexp = "^(?=.*\\d)(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).{8,}$",
                message = "密码强度不足：密码长度不得少于8位，必须同时包含数字、英文字母和特殊字符")
        String password,

        @NotBlank(message = "确认密码不能为空")
        String confirmPassword) {
}