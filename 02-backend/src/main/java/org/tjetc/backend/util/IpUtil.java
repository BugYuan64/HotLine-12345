package org.tjetc.backend.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 获取（登录日志记录、验证码 IP 限频用）
 * 直接取 TCP 连接对端地址：X-Forwarded-For/X-Real-IP 等请求头可由客户端任意伪造，
 * 若信任这些头将导致 IP 限频被绕过（每次换一个伪造头即可）
 */
public final class IpUtil {

    private IpUtil() {
    }

    /**
     * 获取客户端真实 IP：直连场景返回 socket 对端地址；
     * 部署于可信反向代理（如 Nginx）后且代理会覆盖该头时，方可切换为解析固定可信头
     */
    public static String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
