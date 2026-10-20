package org.tjetc.backend.service.common;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存封装（API 1.3 技术选型：验证码、登录失败锁、Token 黑名单、热点缓存）
 * Redis Stack 单实例同时承载 Java 缓存与 Python AI 服务向量检索
 */
@Service
@RequiredArgsConstructor
public class RedisService {

    /** 短信验证码：sms:code:{scene}:{phone} */
    public static final String SMS_CODE_PREFIX = "sms:code:";
    /** 短信重发限制：sms:retry:{phone} */
    public static final String SMS_RETRY_PREFIX = "sms:retry:";
    /** 短信 IP 限频：sms:ip:{ip} */
    public static final String SMS_IP_PREFIX = "sms:ip:";
    /** 邮箱验证码：email:code:{scene}:{email} */
    public static final String EMAIL_CODE_PREFIX = "email:code:";
    /** 邮箱重发限制：email:retry:{email} */
    public static final String EMAIL_RETRY_PREFIX = "email:retry:";
    /** 邮箱 IP 限频：email:ip:{ip} */
    public static final String EMAIL_IP_PREFIX = "email:ip:";
    /** 图形验证码：captcha:{captchaId}（一次性） */
    public static final String CAPTCHA_PREFIX = "captcha:";
    /** 登录失败计数：login:fail:{jobNo} */
    public static final String LOGIN_FAIL_PREFIX = "login:fail:";
    /** 登录锁定：login:lock:{jobNo} */
    public static final String LOGIN_LOCK_PREFIX = "login:lock:";
    /** Token 黑名单：token:blacklist:{token} */
    public static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";
    /** 密码版本号：pwd:ver:{userId}（修改密码后递增，旧 Token 全部失效） */
    public static final String PWD_VERSION_PREFIX = "pwd:ver:";
    /** 短信验证码失败计数：sms:code:fail:{scene}:{phone}（防爆破） */
    public static final String SMS_CODE_FAIL_PREFIX = "sms:code:fail:";
    /** 邮箱验证码失败计数：email:code:fail:{scene}:{email}（防爆破） */
    public static final String EMAIL_CODE_FAIL_PREFIX = "email:code:fail:";

    private final StringRedisTemplate redis;

    public void set(String key, String value, long seconds) {
        // 设置缓存值
        redis.opsForValue().set(key, value, Duration.ofSeconds(seconds));
    }

    public String get(String key) {
        // 获取数据
        return redis.opsForValue().get(key);
    }

    public void delete(String key) {
        // 删除数据
        redis.delete(key);
    }

    public boolean hasKey(String key) {
        // 判断键是否存在
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    public long ttlSeconds(String key) {
        // 执行ttlSeconds操作
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        return ttl == null ? -1 : ttl;
    }

    /**
     * 原子自增并设置过期时间（首次写入时设置 TTL），返回自增后的值
     */
    public long increment(String key, long seconds) {
        // 执行increment操作
        Long value = redis.opsForValue().increment(key);
        if (value != null && value == 1L && seconds > 0) {
            redis.expire(key, Duration.ofSeconds(seconds));
        }
        return value == null ? 0 : value;
    }
}