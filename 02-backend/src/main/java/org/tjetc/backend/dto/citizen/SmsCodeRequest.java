package org.tjetc.backend.dto.citizen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 发送短信验证码（API 3.2）：手机号 + 场景
 * 注：图形验证码已改为前端滑动验证，captchaId/captchaCode 可选
 */
public record SmsCodeRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "场景不能为空")
        @Pattern(regexp = "^(LOGIN|REGISTER|RESET_PASSWORD|DEPT_REGISTER|STAFF_REGISTER|STREET_REGISTER)$",
                message = "场景必须为LOGIN/REGISTER/RESET_PASSWORD/DEPT_REGISTER/STAFF_REGISTER/STREET_REGISTER之一")
        String scene,

        @JsonProperty("captcha_id")
        String captchaId,

        @JsonProperty("captcha_code")
        String captchaCode) {
}
