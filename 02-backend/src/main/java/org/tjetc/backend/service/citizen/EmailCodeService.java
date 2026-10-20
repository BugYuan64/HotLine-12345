package org.tjetc.backend.service.citizen;

import cn.hutool.core.util.RandomUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.service.common.EmailSender;
import org.tjetc.backend.service.common.RedisService;

/**
 * 邮箱验证码服务（注册邮箱验证码功能，QQ邮箱发信）：
 * - 6 位数字验证码，有效期 5 分钟，Redis 缓存
 * - 防刷：同邮箱 60 秒内禁止重发、同 IP 限频
 * - 验证码一次性，校验通过即失效（与短信验证码 SmsService 对称）
 */
@Service
@RequiredArgsConstructor
public class EmailCodeService {

    @Value("${app.mail.code-expire-seconds:300}")
    private long codeExpireSeconds;

    @Value("${app.mail.retry-seconds:60}")
    private long retrySeconds;

    @Value("${app.mail.ip-limit-per-minute:10}")
    private int ipLimitPerMinute;

    /** 验证码最大失败尝试次数（防爆破：与 SmsService 对称） */
    private static final int MAX_VERIFY_FAIL = 5;

    private final RedisService redisService;
    private final EmailSender emailSender;

    /**
     * 发送验证码，返回 {expire_seconds, retry_after}
     */
    public int[] send(String email, String scene, String ip) {
        // 同邮箱 60 秒内禁止重发
        if (redisService.hasKey(RedisService.EMAIL_RETRY_PREFIX + email)) {
            throw new BusinessException(ResultCode.SMS_CODE_ERROR,
                    "验证码发送过于频繁，请" + redisService.ttlSeconds(RedisService.EMAIL_RETRY_PREFIX + email) + "秒后再试");
        }
        // 同 IP 限频，防恶意刷取
        long ipCount = redisService.increment(RedisService.EMAIL_IP_PREFIX + ip, 60);
        if (ipCount > ipLimitPerMinute) {
            throw new BusinessException(ResultCode.SMS_CODE_ERROR, "验证码发送过于频繁，请稍后再试");
        }
        String code = RandomUtil.randomNumbers(6);
        redisService.set(RedisService.EMAIL_CODE_PREFIX + scene + ":" + email, code, codeExpireSeconds);
        redisService.set(RedisService.EMAIL_RETRY_PREFIX + email, "1", retrySeconds);
        try {
            emailSender.send(email, code);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.THIRD_PARTY_ERROR, "邮件发送失败，请稍后再试");
        }
        return new int[]{(int) codeExpireSeconds, (int) retrySeconds};
    }

    /**
     * 校验验证码（一次性：校验通过即删除）；
     * 连续失败5次直接作废验证码，防止有效期内暴力穷举
     */
    public void verify(String email, String scene, String code) {
        // 校验邮箱验证码
        String key = RedisService.EMAIL_CODE_PREFIX + scene + ":" + email;
        String cached = redisService.get(key);
        if (cached == null || !cached.equals(code)) {
            // 失败计数：达到上限删除验证码（攻击者需重新触发发送，受图形验证码与限频约束）
            String failKey = RedisService.EMAIL_CODE_FAIL_PREFIX + scene + ":" + email;
            long fails = redisService.increment(failKey, codeExpireSeconds);
            if (fails >= MAX_VERIFY_FAIL) {
                redisService.delete(key);
                redisService.delete(failKey);
                throw new BusinessException(ResultCode.SMS_CODE_ERROR, "尝试次数过多，验证码已作废，请重新获取");
            }
            throw new BusinessException(ResultCode.SMS_CODE_ERROR, "邮箱验证码错误或已过期");
        }
        redisService.delete(key);
        redisService.delete(RedisService.EMAIL_CODE_FAIL_PREFIX + scene + ":" + email);
    }
}
