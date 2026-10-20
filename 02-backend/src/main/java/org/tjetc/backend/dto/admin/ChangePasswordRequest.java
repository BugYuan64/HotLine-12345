package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 修改登录密码（API 9.10）
 */
public record ChangePasswordRequest(
        @NotBlank(message = "原密码不能为空")
        @JsonProperty("old_password")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Pattern(regexp = "^(?=.*\\d)(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).{8,}$",
                message = "密码强度不足：密码长度不得少于8位，必须同时包含数字、英文字母和特殊字符")
        @JsonProperty("new_password")
        String newPassword,

        @NotBlank(message = "确认新密码不能为空")
        @JsonProperty("confirm_password")
        String confirmPassword) {
}
