package org.tjetc.backend.security;

import cn.hutool.jwt.JWT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.tjetc.backend.common.*;
import org.tjetc.backend.mapper.common.SysUserMapper;
import org.tjetc.backend.service.common.RedisService;
import org.tjetc.backend.util.JwtUtil;

import java.util.Set;

/**
 * JWT 认证拦截器（API 接口文档 2.2/2.5/2.6）：
 * - 免 Token 白名单：注册 / 短信验证码 / 市民登录 / 管理员登录 / 部门人员登录
 * - Token 黑名单校验（退出登录后 Token 失效，40102）
 * - 按接口前缀做用户类型权限隔离：/api/v1/admin/** 仅 ADMIN，/api/v1/staff/** 仅部门/街道办，
 *   /api/v1/public/** 仅市民，/api/v1/common/** 所有已登录角色
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 免 Token 白名单（API 13.x 索引中标明认证=否 的接口） */
    private static final Set<String> PUBLIC_URIS = Set.of(
            "/api/v1/public/staff-register",
            "/api/v1/public/citizen-register",
            "/api/v1/public/login-email",
            "/api/v1/public/reset-password",
            "/api/v1/public/sms/code",
            "/api/v1/public/email/code",
            "/api/v1/public/captcha",
            "/api/v1/public/login",
            "/api/v1/public/regions/counties",
            "/api/v1/public/regions/children",
            "/api/v1/admin/auth/login",
            "/api/v1/staff/auth/login",
            "/api/v1/staff/register",
            "/api/v1/street/auth/login",
            "/api/v1/street/register");

    /** 部门端用户类型（API 14.1 user_type）：8 大分派部门 + 保留物业/民生（不含 STREET） */
    private static final Set<String> DEPT_TYPES = Set.of(
            "URBAN", "PROPERTY", "LIVELIHOOD",
            "TRAFFIC", "MARKET", "ENVIRONMENT", "HOUSING", "LABOR", "PUBLIC", "HEALTH");

    /** 街道办用户类型：仅 STREET（走 /api/v1/street/** 独立后端） */
    private static final Set<String> STREET_TYPES = Set.of("STREET");

    private final JwtUtil jwtUtil;
    private final RedisService redisService;
    private final SysUserMapper userMapper;

    public AuthInterceptor(JwtUtil jwtUtil, RedisService redisService, SysUserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.redisService = redisService;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 请求前置处理
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if (PUBLIC_URIS.contains(uri)) {
            return true;
        }
        // 仅拦截 /api/ 下的业务接口
        if (!uri.startsWith("/api/")) {
            return true;
        }

        String token = resolveToken(request);
        if (token == null) {
            return reject(response, ResultCode.UNAUTHORIZED);
        }
        // Token 黑名单校验（退出登录后失效）
        if (redisService.hasKey(RedisService.TOKEN_BLACKLIST_PREFIX + token)) {
            return reject(response, ResultCode.TOKEN_INVALID);
        }
        JWT jwt = jwtUtil.verify(token);
        if (jwt == null) {
            return reject(response, ResultCode.TOKEN_INVALID);
        }

        String userType = JwtUtil.getUserType(jwt);
        LoginUser user = new LoginUser(JwtUtil.getUserId(jwt), userType,
                JwtUtil.getRegion(jwt), JwtUtil.getRegionCode(jwt),
                JwtUtil.getJobNo(jwt), JwtUtil.getPhone(jwt));

        // 密码版本校验：修改密码后版本递增，旧 Token 携带版本不一致立即失效。
        // Redis 缓存缺失时回退 DB（sys_user.pwd_version 为事实源）并回写缓存，
        // 避免 Redis 重启/数据丢失后版本被误判为 0 导致有效 Token 大面积失效
        if (user.userId() != null) {
            String cached = redisService.get(RedisService.PWD_VERSION_PREFIX + user.userId());
            long redisVer;
            if (cached != null) {
                redisVer = Long.parseLong(cached);
            } else {
                Integer dbVer = userMapper.selectPwdVersion(user.userId());
                redisVer = dbVer == null ? 0L : dbVer;
                redisService.set(RedisService.PWD_VERSION_PREFIX + user.userId(),
                        String.valueOf(redisVer), 30L * 24 * 3600);
            }
            if (JwtUtil.getPwdVersion(jwt) != redisVer) {
                return reject(response, ResultCode.TOKEN_INVALID);
            }
        }

        // 按前缀做用户类型权限隔离（公众端与政务后台完全隔离，前端不可越权）
        if (uri.startsWith("/api/v1/admin/")) {
            if (!"ADMIN".equals(userType)) {
                return reject(response, ResultCode.ADMIN_FORBIDDEN);
            }
        } else if (uri.startsWith("/api/v1/staff/")) {
            if (!DEPT_TYPES.contains(userType)) {
                return reject(response, ResultCode.FORBIDDEN);
            }
        } else if (uri.startsWith("/api/v1/street/")) {
            if (!STREET_TYPES.contains(userType)) {
                return reject(response, ResultCode.FORBIDDEN);
            }
        } else if (uri.startsWith("/api/v1/public/")) {
            if (!"CITIZEN".equals(userType)) {
                return reject(response, ResultCode.FORBIDDEN);
            }
        }
        // /api/v1/common/** 所有已登录角色可用

        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        // 请求完成后处理
        UserContext.clear();
    }

    /** 认证/权限失败：直接写统一 JSON 响应并中断请求（不走异常处理器——
     *  SSE 请求 Accept 为 text/event-stream，异常处理器无法协商出可写类型会抛 No acceptable representation 变 500） */
    private boolean reject(HttpServletResponse response, ResultCode code) throws Exception {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code.getCode()
                + ",\"message\":\"" + code.getMessage()
                + "\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() / 1000 + "}");
        response.getWriter().flush();
        return false;
    }

    private String resolveToken(HttpServletRequest request) {
        // 解析Token
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        // SSE（EventSource 无法自定义请求头）：从 query 参数读 token
        return request.getParameter("token");
    }
}
