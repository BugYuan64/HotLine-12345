package org.tjetc.backend.dto.citizen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 市民验证码登录（API 3.3）：手机号 + 短信验证码
 * 注：图形验证码已改为前端滑动验证，captchaId/captchaCode 可选
 */
public record CitizenLoginRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "短信验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码为6位数字")
        @JsonProperty("sms_code")
        String smsCode,

        @JsonProperty("captcha_id")
        String captchaId,

        @JsonProperty("captcha_code")
        String captchaCode) {
}
