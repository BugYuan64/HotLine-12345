package org.tjetc.backend.common;

/**
 * 当前登录用户上下文信息（由 AuthInterceptor 从 JWT 解析填充）
 */
public record LoginUser(Long userId, String userType, String region, String regionCode, String jobNo, String phone) {
}
