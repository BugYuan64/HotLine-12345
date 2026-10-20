package org.tjetc.backend.dto.citizen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 市民自助注册（API 3.2 扩展）：手机号 + 短信验证码，辖区/街道用区划下拉，字段名与前端提交一致
 */
public record CitizenRegisterRequest(
        @NotBlank(message = "姓名不能为空")
        String name,

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "短信验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码为6位数字")
        @JsonProperty("sms_code")
        String smsCode,

        @NotBlank(message = "登录密码不能为空")
        // 密码强度：长度≥8 且同时包含数字/英文字母/特殊字符
        //（(?=.*\\d) 任意位置含数字，旧版 (?=\\d) 误要求"首字符是数字"，如 Admin@12345 会被误拒）
        @Pattern(regexp = "^(?=.*\\d)(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).{8,}$",
                message = "密码强度不足：密码长度不得少于8位，必须同时包含数字、英文字母和特殊字符")
        String password,

        @NotBlank(message = "确认密码不能为空")
        @JsonProperty("confirm_password")
        String confirmPassword,

        String email,

        @NotBlank(message = "请选择辖区")
        @JsonProperty("district_code")
        String districtCode,

        @NotBlank(message = "辖区名称不能为空")
        String district,

        @NotBlank(message = "请选择街道")
        @JsonProperty("street_code")
        String streetCode,

        @NotBlank(message = "街道名称不能为空")
        String street,

        @NotBlank(message = "详细住址不能为空")
        @JsonProperty("address_detail")
        String addressDetail) {
}
