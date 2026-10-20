package org.tjetc.backend.controller.citizen;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.service.common.CaptchaService;
import org.tjetc.backend.util.IpUtil;
import org.tjetc.backend.dto.citizen.CitizenEmailLoginRequest;
import org.tjetc.backend.dto.citizen.CitizenLoginRequest;
import org.tjetc.backend.dto.citizen.EmailCodeRequest;
import org.tjetc.backend.dto.citizen.ResetPasswordRequest;
import org.tjetc.backend.dto.citizen.SmsCodeRequest;
import org.tjetc.backend.dto.citizen.CitizenRegisterRequest;
import org.tjetc.backend.dto.citizen.StaffRegisterRequest;
import org.tjetc.backend.service.citizen.AuthService;
import org.tjetc.backend.service.citizen.EmailCodeService;
import org.tjetc.backend.service.citizen.SmsService;
import org.tjetc.backend.service.common.RegionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公众端认证接口（API 3.1/3.2/3.3）：
 * 工作人员自助注册 / 发送短信验证码 / 发送邮箱验证码 / 图形验证码 / 市民验证码登录
 * 登录与发送验证码均校验图形验证码（防人机）
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/public")
public class PublicAuthController {

    private final AuthService authService;
    private final SmsService smsService;
    private final EmailCodeService emailCodeService;
    private final CaptchaService captchaService;
    private final RegionService regionService;

    /**
     * 获取图形验证码（登录/发送验证码前置，防人机）
     */
    @GetMapping("/captcha")
    public Result<Map<String, Object>> captcha() {
        // 生成图形验证码
        return Result.ok(captchaService.generate());
    }

    /**
     * 工作人员自助注册（API 3.1）：邮箱验证码校验在 AuthService 内完成
     */
    @PostMapping("/staff-register")
    public Result<Map<String, Object>> staffRegister(@Valid @RequestBody StaffRegisterRequest request) {
        // 执行staffRegister操作
        return Result.ok(authService.register(request));
    }

    /** 市民自助注册（API 3.2 扩展）：手机号 + QQ 邮箱验证码 + 密码登记 */
    @PostMapping("/citizen-register")
    public Result<Map<String, Object>> citizenRegister(@Valid @RequestBody CitizenRegisterRequest request) {
        return Result.ok(authService.citizenRegister(request));
    }

    /** 市民邮箱密码登录：email + bcrypt 校验 */
    @PostMapping("/login-email")
    public Result<Map<String, Object>> citizenEmailLogin(@Valid @RequestBody CitizenEmailLoginRequest request,
                                                         HttpServletRequest http) {
        return Result.ok(authService.citizenEmailLogin(request, IpUtil.getClientIp(http),
                http.getHeader("User-Agent")));
    }

    /** 市民个人资料（个人中心展示）：按登录手机号查询 citizen_account */
    @GetMapping("/citizen/profile")
    public Result<Map<String, Object>> citizenProfile() {
        LoginUser user = UserContext.get();
        return Result.ok(authService.citizenProfile(user.phone()));
    }

    /**
     * 区划字典：查询全部区县（街道办注册的区县下拉）
     */
    @GetMapping("/regions/counties")
    public Result<List<Map<String, String>>> counties() {
        // 返回区县下拉：regin_code + region_name
        List<Map<String, String>> list = regionService.listCounties().stream()
                .map(r -> Map.of("region_code", r.getRegionCode(), "region_name", r.getRegionName()))
                .toList();
        return Result.ok(list);
    }

    /**
     * 区划字典：查询某区县下的街道/镇/乡（街道办注册的乡镇级下拉）
     */
    @GetMapping("/regions/children")
    public Result<List<Map<String, String>>> regionChildren(@RequestParam("parent") String parentCode) {
        // 返回乡镇级下拉：region_code + region_name + level
        List<Map<String, String>> list = regionService.listChildren(parentCode).stream()
                .map(r -> Map.of("region_code", r.getRegionCode(),
                        "region_name", r.getRegionName(), "level", r.getLevel()))
                .toList();
        return Result.ok(list);
    }

    /**
     * 忘记密码（部门工作人员）：手机号 + 短信验证码 重置登录密码
     */
    @PostMapping("/reset-password")
    public Result<Map<String, Object>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        // 执行resetPassword操作
        return Result.ok(authService.resetPassword(request));
    }

    /**
     * 发送短信验证码（API 3.2）：图形验证码改为前端滑动验证，此处不再强制校验。
     * 市民登录场景：手机号必须已注册（未注册不发码，提示先注册，与邮箱登录口径一致）
     */
    @PostMapping("/sms/code")
    public Result<Map<String, Object>> sendSmsCode(@Valid @RequestBody SmsCodeRequest request,
                                                   HttpServletRequest http) {
        // 发送SmsCode：前端滑动验证已替代图形验证码；兼容旧客户端，仅当传了图形验证码才做校验
        if (request.captchaId() != null && !request.captchaId().isBlank()
                && request.captchaCode() != null && !request.captchaCode().isBlank()) {
            captchaService.verify(request.captchaId(), request.captchaCode(), false);
        }
        // 市民登录场景：仅向已注册手机号发送（未注册不允许登录，也不发送验证码）
        if ("LOGIN".equals(request.scene())) {
            authService.requireCitizenPhoneRegistered(request.phone());
        }
        int[] result = smsService.send(request.phone(), request.scene(), IpUtil.getClientIp(http));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expire_seconds", result[0]);
        data.put("retry_after", result[1]);
        return Result.ok(data);
    }

    /**
     * 发送邮箱验证码：登录场景仅向已注册市民邮箱发送（未注册提示先注册）；注册场景不限制。
     * 图形验证码改为前端滑动验证，此处不再强制校验。
     */
    @PostMapping("/email/code")
    public Result<Map<String, Object>> sendEmailCode(@Valid @RequestBody EmailCodeRequest request,
                                                     HttpServletRequest http) {
        // 若前端传了 captchaId + captchaCode 则校验（兼容旧版），否则跳过
        if (request.captchaId() != null && request.captchaCode() != null
                && !request.captchaId().isBlank() && !request.captchaCode().isBlank()) {
            captchaService.verify(request.captchaId(), request.captchaCode(), false);
        }
        // 市民登录场景：邮箱必须已注册（未注册不允许登录，也不发送验证码）
        if ("LOGIN".equals(request.scene())) {
            authService.requireCitizenEmailRegistered(request.email());
        }
        int[] result = emailCodeService.send(request.email(), request.scene(), IpUtil.getClientIp(http));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expire_seconds", result[0]);
        data.put("retry_after", result[1]);
        return Result.ok(data);
    }

    /**
     * 市民验证码登录（API 3.3）：图形验证码改为前端滑动验证，此处不再强制校验
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> citizenLogin(@Valid @RequestBody CitizenLoginRequest request,
                                                    HttpServletRequest http) {
        // 前端滑动验证已替代图形验证码；兼容旧客户端，仅当传了图形验证码才做校验
        if (request.captchaId() != null && !request.captchaId().isBlank()
                && request.captchaCode() != null && !request.captchaCode().isBlank()) {
            captchaService.verify(request.captchaId(), request.captchaCode(), true);
        }
        return Result.ok(authService.citizenLogin(request, IpUtil.getClientIp(http),
                http.getHeader("User-Agent")));
    }

    /**
     * 退出登录：Token 加入 Redis 黑名单（API 2.2 Token 注销；需求 3(14)）
     * 说明：接口文档未定义该路径，采用 /logout，待确认
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest http) {
        // 用户登出
        String token = http.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            authService.logout(token.substring(7));
        }
        return Result.ok();
    }
}