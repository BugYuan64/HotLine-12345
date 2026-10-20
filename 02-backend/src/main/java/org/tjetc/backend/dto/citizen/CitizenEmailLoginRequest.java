package org.tjetc.backend.dto.citizen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 市民邮箱验证码登录：email + 邮箱验证码（注册后可用；验证码经 /public/email/code 发送至邮箱）
 */
public record CitizenEmailLoginRequest(
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        String email,

        @NotBlank(message = "邮箱验证码不能为空")
        @JsonProperty("email_code")
        String emailCode) {
}
