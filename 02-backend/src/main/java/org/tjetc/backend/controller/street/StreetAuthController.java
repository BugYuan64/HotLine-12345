package org.tjetc.backend.controller.street;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.dto.citizen.JobNoLoginRequest;
import org.tjetc.backend.dto.citizen.StreetRegisterRequest;
import org.tjetc.backend.service.common.CaptchaService;
import org.tjetc.backend.service.citizen.AuthService;
import org.tjetc.backend.util.IpUtil;

import java.util.Map;
import java.util.Set;

/**
 * 街道办端认证接口：
 * 独立于部门端的登录入口。仅放行 user_type=STREET 的账号，
 * 与服务端部门端登录接口（仅放行 8 大部门）形成「登录界面 + 字段」双重隔离，
 * 确保街道办账号只能经此入口登录，不能通过部门端入口登录，反之亦然。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/street")
public class StreetAuthController {

    // 街道办端允许的 user_type：仅 STREET（街道工作人员，按辖区 region 划分）
    private static final Set<String> STREET_TYPES = Set.of("STREET");

    private final AuthService authService;
    private final CaptchaService captchaService;

    /**
     * 街道办人员登录：工号 / 手机号 + 密码 + 前端滑动验证码。
     * 后端按 user_type 精确校验，非 STREET 一律拒绝。
     */
    @PostMapping("/auth/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody JobNoLoginRequest request,
                                             HttpServletRequest http) {
        // 若前端传了 captchaId + captchaCode 则校验（兼容旧版），否则跳过（前端用滑动验证）
        if (request.captchaId() != null && request.captchaCode() != null
                && !request.captchaId().isBlank() && !request.captchaCode().isBlank()) {
            captchaService.verify(request.captchaId(), request.captchaCode());
        }
        return Result.ok(authService.jobNoLogin(request, STREET_TYPES,
                IpUtil.getClientIp(http), http.getHeader("User-Agent")));
    }

    /**
     * 街道办工作人员自助注册：姓名 + 工号 + 区县 + 街道/镇/乡 + 手机号 + 短信验证码，提交后待管理员审核
     */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Valid @RequestBody StreetRegisterRequest request) {
        // 街道办注册（工号由用户手动填写，地区按区县→乡镇级两级联动）
        return Result.ok(authService.streetRegister(request));
    }

    /**
     * 街道办登出：独立于部门端 /staff/auth/logout。
     * 部门端前缀接口被 AuthInterceptor 限定为职能部门（DEPT_TYPES）调用，
     * 街道办（STREET）走本接口避免被 403 拦截。
     */
    @PostMapping("/auth/logout")
    public Result<Void> logout(HttpServletRequest http) {
        String token = http.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            authService.logout(token.substring(7));
        }
        return Result.ok();
    }
}