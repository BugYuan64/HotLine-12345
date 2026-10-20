package org.tjetc.backend.dto.citizen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 发送邮箱验证码（注册邮箱验证码功能）：邮箱 + 场景
 * 注：图形验证码已改为前端滑动验证，captchaId/captchaCode 可选
 */
public record EmailCodeRequest(
        @NotBlank(message = "邮箱不能为空")
        @Pattern(regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", message = "邮箱格式不正确")
        String email,

        @NotBlank(message = "场景不能为空")
        @Pattern(regexp = "^(LOGIN|REGISTER)$", message = "场景必须为LOGIN/REGISTER之一")
        String scene,

        @JsonProperty("captcha_id")
        String captchaId,

        @JsonProperty("captcha_code")
        String captchaCode) {
}
