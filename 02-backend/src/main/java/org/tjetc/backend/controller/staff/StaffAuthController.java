package org.tjetc.backend.controller.staff;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.tjetc.backend.common.*;
import org.tjetc.backend.dto.admin.ChangePasswordRequest;
import org.tjetc.backend.dto.citizen.JobNoLoginRequest;
import org.tjetc.backend.dto.citizen.StaffDeptRegisterRequest;
import org.tjetc.backend.service.common.CaptchaService;
import org.tjetc.backend.service.citizen.AuthService;
import org.tjetc.backend.service.admin.SysLoginLogService;
import org.tjetc.backend.util.IpUtil;

import java.util.Map;
import java.util.Set;

/**
 * 部门端认证与个人基础接口（API 9.1/9.10/9.11）：
 * 部门/街道办人员工号 + 密码登录 + 图形验证码 / 修改登录密码 / 个人登录日志查询
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/staff")
public class StaffAuthController {

    // 部门端允许的 user_type：8 大分派部门 + 历史遗留部门（PROPERTY/LIVELIHOOD，兼容存量账号），
    // 与 AuthInterceptor.DEPT_TYPES 一致，避免注册/审核通过后无法登录的死账号。
    // 已从该集合移除 STREET：街道办账号只能走 /api/v1/street/auth/login 独立入口，
    // 实现「登录界面 + 字段」双重隔离，确保部门端与街道办端不能相互登录。
    private static final Set<String> STAFF_TYPES = Set.of(
            "URBAN", "TRAFFIC", "MARKET", "ENVIRONMENT", "HOUSING", "LABOR", "PUBLIC", "HEALTH",
            "PROPERTY", "LIVELIHOOD");

    private final AuthService authService;
    private final SysLoginLogService loginLogService;
    private final CaptchaService captchaService;

    /**
     * 部门/街道办人员登录（API 9.1）：图形验证码改为前端滑动验证，此处不再强制校验
     */
    @PostMapping("/auth/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody JobNoLoginRequest request,
                                             HttpServletRequest http) {
        // 若前端传了 captchaId + captchaCode 则校验（兼容旧版），否则跳过
        if (request.captchaId() != null && request.captchaCode() != null
                && !request.captchaId().isBlank() && !request.captchaCode().isBlank()) {
            captchaService.verify(request.captchaId(), request.captchaCode());
        }
        return Result.ok(authService.jobNoLogin(request, STAFF_TYPES,
                IpUtil.getClientIp(http), http.getHeader("User-Agent")));
    }

    /**
     * 部门工作人员自助注册：姓名 + 工号 + 手机号 + 部门类型 + 短信验证码，提交后待管理员审核
     */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Valid @RequestBody StaffDeptRegisterRequest request) {
        // 部门注册（工号由用户手动填写，不再自动分配）
        return Result.ok(authService.staffDeptRegister(request));
    }

    /**
     * 退出登录（API 2.2 Token 注销）
     * 说明：接口文档未定义该路径，采用 /logout，待确认
     */
    @PostMapping("/auth/logout")
    public Result<Void> logout(HttpServletRequest http) {
        // 用户登出
        String token = http.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            authService.logout(token.substring(7));
        }
        return Result.ok();
    }

    /**
     * 修改登录密码（API 9.10）
     */
    @PutMapping("/profile/password")
    public Result<Map<String, Object>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        // 修改密码
        authService.changePassword(UserContext.get().userId(), request);
        return Result.ok(Map.of("updated", true));
    }

    /**
     * 个人登录日志查询（API 9.11）
     */
    @GetMapping("/profile/logs")
    public Result<Map<String, Object>> loginLogs(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 登录Logs
        return Result.page(loginLogService.pageByUserId(UserContext.get().userId(), page, pageSize));
    }
}