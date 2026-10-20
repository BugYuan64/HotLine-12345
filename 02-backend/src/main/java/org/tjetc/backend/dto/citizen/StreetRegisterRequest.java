package org.tjetc.backend.dto.citizen;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 街道办工作人员自助注册（新）：姓名 + 工号 + 区县 + 街道/镇/乡 + 手机号 + 短信验证码 + 密码。
 * 工号由用户手动填写（入职已有工号），不再自动分配。
 * 地区按「区县 countyCode → 街道/镇/乡 streetCode」两级级联选择，区划编码来自 sys_region 表。
 */
public record StreetRegisterRequest(

        @NotBlank(message = "姓名不能为空")
        String name,

        @NotBlank(message = "工号不能为空")
        @Pattern(regexp = "^[A-Za-z0-9]{6,20}$", message = "工号仅支持字母与数字组合，长度限制6-20位")
        String jobNo,

        @NotBlank(message = "请选择所属区县")
        String countyCode,

        @NotBlank(message = "请选择所属街道/镇/乡")
        String streetCode,

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "短信验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码为6位数字")
        String smsCode,

        @NotBlank(message = "登录密码不能为空")
        @Pattern(regexp = "^(?=.*\\d)(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).{8,}$",
                message = "密码强度不足：密码长度不得少于8位，必须同时包含数字、英文字母和特殊字符")
        String password,

        @NotBlank(message = "确认密码不能为空")
        String confirmPassword) {
}