package org.tjetc.backend.util;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * JWT 令牌生成与解析（API 接口文档 2.2 认证方式）：
 * - token 中携带用户 ID、角色、归属信息（user_type/region），并设置有效期
 * - 市民端短期 Token 默认 2 小时有效
 * - 携带 pwdVersion（密码版本）：修改密码后版本变化，旧 Token 立即失效
 */
@Component
public class JwtUtil {

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_USER_TYPE = "userType";
    public static final String CLAIM_REGION = "region";
    public static final String CLAIM_JOB_NO = "jobNo";
    public static final String CLAIM_PHONE = "phone";
    public static final String CLAIM_PWD_VERSION = "pwdVersion";
    public static final String CLAIM_REGION_CODE = "regionCode";

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expire-seconds:7200}")
    private long expireSeconds;

    /**
     * 启动时校验密钥强度：空或短于32字符直接拒绝启动，
     * 防止弱密钥被暴力破解后伪造任意身份 Token
     */
    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT 密钥未配置：请设置环境变量 JWT_SECRET（至少32字符随机串，"
                            + "可用 openssl rand -base64 48 生成）后再启动");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException(
                    "JWT 密钥强度不足（" + secret.length() + " 字符）：密钥须至少32字符，请重新生成并设置环境变量 JWT_SECRET");
        }
    }

    public String createToken(Long userId, String userType, String region, String jobNo, String phone) {
        // 生成JWT令牌（兼容旧调用：不携带区划编码，视为无区划）
        return createToken(userId, userType, region, null, jobNo, phone, 0L);
    }

    /** 生成携带密码版本的 JWT：版本与 Redis 中不一致时 Token 失效（改密踢出旧会话）
     *  兼容旧调用：不带区划编码 */
    public String createToken(Long userId, String userType, String region, String jobNo, String phone,
                              long pwdVersion) {
        // 生成JWT令牌
        return createToken(userId, userType, region, null, jobNo, phone, pwdVersion);
    }

    /** 生成携带区划编码与密码版本的 JWT：部门账号携带区县级 region_code，用于部门端按区县过滤 */
    public String createToken(Long userId, String userType, String region, String regionCode,
                              String jobNo, String phone, long pwdVersion) {
        // 生成JWT令牌
        JWT jwt = JWT.create()
                .setIssuedAt(new Date())
                .setExpiresAt(new Date(System.currentTimeMillis() + expireSeconds * 1000))
                .setPayload(CLAIM_USER_ID, userId)
                .setPayload(CLAIM_USER_TYPE, userType)
                .setPayload(CLAIM_REGION, region)
                .setPayload(CLAIM_REGION_CODE, regionCode)
                .setPayload(CLAIM_JOB_NO, jobNo)
                .setPayload(CLAIM_PHONE, phone)
                .setPayload(CLAIM_PWD_VERSION, pwdVersion)
                .setKey(secret.getBytes());
        return jwt.sign();
    }

    /**
     * 校验签名与有效期，成功返回 payload，失败返回 null
     */
    public JWT verify(String token) {
        // 校验验证
        try {
            if (!JWTUtil.verify(token, secret.getBytes())) {
                return null;
            }

            JWT jwt = JWTUtil.parseToken(token);
            // 需先设置密钥：validate 会连带做算法校验，未设 key 时因"无签名器"而判为无效
            jwt.setKey(secret.getBytes());
            if (!jwt.validate(0)) {
                return null;
            }
            return jwt;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 剩余有效期（秒），用于注销时黑名单 TTL 对齐 Token 剩余有效期（API 2.2）
     */
    public long remainingSeconds(String token) {
        // 获取剩余有效期
        JWT jwt = JWTUtil.parseToken(token);
        Object exp = jwt.getPayload("exp");
        if (exp instanceof Number n) {
            return Math.max(0, n.longValue() - System.currentTimeMillis() / 1000);
        }
        return 0;
    }

    public static Long getUserId(JWT jwt) {
        Object v = jwt.getPayload(CLAIM_USER_ID);
        return v instanceof Number n ? n.longValue() : null;
    }

    public static String getUserType(JWT jwt) {
        Object v = jwt.getPayload(CLAIM_USER_TYPE);
        return v == null ? null : v.toString();
    }

    public static String getRegion(JWT jwt) {
        Object v = jwt.getPayload(CLAIM_REGION);
        return v == null ? null : v.toString();
    }

    public static String getRegionCode(JWT jwt) {
        Object v = jwt.getPayload(CLAIM_REGION_CODE);
        return v == null ? null : v.toString();
    }

    public static String getJobNo(JWT jwt) {
        Object v = jwt.getPayload(CLAIM_JOB_NO);
        return v == null ? null : v.toString();
    }

    public static String getPhone(JWT jwt) {
        Object v = jwt.getPayload(CLAIM_PHONE);
        return v == null ? null : v.toString();
    }

    /** 读取 Token 中密码版本（无该 claim 的存量 Token 视为版本0） */
    public static long getPwdVersion(JWT jwt) {
        Object v = jwt.getPayload(CLAIM_PWD_VERSION);
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
