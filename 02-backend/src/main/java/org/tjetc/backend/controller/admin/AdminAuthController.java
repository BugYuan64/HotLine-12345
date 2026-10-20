package org.tjetc.backend.controller.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.service.common.CaptchaService;
import org.tjetc.backend.util.IpUtil;
import org.tjetc.backend.dto.citizen.JobNoLoginRequest;
import org.tjetc.backend.service.citizen.AuthService;

import java.util.Map;
import java.util.Set;

/**
 * 管理端认证接口（API 5.1）：政府用户（系统管理员 admin）工号 + 密码登录 + 图形验证码
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {

    private static final Set<String> ADMIN_TYPES = Set.of("ADMIN");

    private final AuthService authService;
    private final CaptchaService captchaService;

    /**
     * 管理员登录（API 5.1）：图形验证码改为前端滑动验证，此处不再强制校验
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody JobNoLoginRequest request,
                                             HttpServletRequest http) {
        // 若前端传了 captchaId + captchaCode 则校验（兼容旧版），否则跳过
        if (request.captchaId() != null && request.captchaCode() != null
                && !request.captchaId().isBlank() && !request.captchaCode().isBlank()) {
            captchaService.verify(request.captchaId(), request.captchaCode());
        }
        return Result.ok(authService.jobNoLogin(request, ADMIN_TYPES,
                IpUtil.getClientIp(http), http.getHeader("User-Agent")));
    }

    /**
     * 退出登录（API 2.2 Token 注销）
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