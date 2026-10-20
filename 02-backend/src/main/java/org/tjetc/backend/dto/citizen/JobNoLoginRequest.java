package org.tjetc.backend.dto.citizen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 工号 + 密码登录（API 5.1 管理员登录 / API 9.1 部门人员登录）
 * 注：图形验证码已改为前端滑动验证，captchaId/captchaCode 可选
 */
public record JobNoLoginRequest(
        @NotBlank(message = "工号不能为空")
        @JsonProperty("job_no")
        String jobNo,

        @NotBlank(message = "登录密码不能为空")
        String password,

        @JsonProperty("captcha_id")
        String captchaId,

        @JsonProperty("captcha_code")
        String captchaCode) {
}
