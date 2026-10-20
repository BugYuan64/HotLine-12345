package org.tjetc.backend.service.citizen;

import cn.hutool.core.util.RandomUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.service.common.RedisService;
import org.tjetc.backend.service.common.SmsSender;

/**
 * 短信验证码服务（API 3.2）：
 * - 6 位数字验证码，有效期 5 分钟，Redis 缓存
 * - 防刷：同手机号 60 秒内禁止重发、同 IP 限频
 * - 验证码一次性，校验通过即失效（API 3.3 技术说明）
 */
@Service
@RequiredArgsConstructor
public class SmsService {

    @Value("${app.sms.code-expire-seconds:300}")
    private long codeExpireSeconds;

    @Value("${app.sms.retry-seconds:60}")
    private long retrySeconds;

    @Value("${app.sms.ip-limit-per-minute:10}")
    private int ipLimitPerMinute;

    /** 验证码最大失败尝试次数（防爆破：6位数字空间100万，限制后5次内必须命中） */
    private static final int MAX_VERIFY_FAIL = 5;

    private final RedisService redisService;
    private final SmsSender smsSender;

    /**
     * 发送验证码，返回 {expire_seconds, retry_after}
     */
    public int[] send(String phone, String scene, String ip) {
        // 同手机号 60 秒内禁止重发
        if (redisService.hasKey(RedisService.SMS_RETRY_PREFIX + phone)) {
            throw new BusinessException(ResultCode.SMS_CODE_ERROR,
                    "验证码发送过于频繁，请" + redisService.ttlSeconds(RedisService.SMS_RETRY_PREFIX + phone) + "秒后再试");
        }
        // 同 IP 限频，防恶意刷取
        long ipCount = redisService.increment(RedisService.SMS_IP_PREFIX + ip, 60);
        if (ipCount > ipLimitPerMinute) {
            throw new BusinessException(ResultCode.SMS_CODE_ERROR, "验证码发送过于频繁，请稍后再试");
        }
        String code = RandomUtil.randomNumbers(6);
        redisService.set(RedisService.SMS_CODE_PREFIX + scene + ":" + phone, code, codeExpireSeconds);
        redisService.set(RedisService.SMS_RETRY_PREFIX + phone, "1", retrySeconds);
        try {
            smsSender.send(phone, code);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.THIRD_PARTY_ERROR, "短信发送失败，请稍后再试");
        }
        return new int[]{(int) codeExpireSeconds, (int) retrySeconds};
    }

    /**
     * 校验验证码（一次性：校验通过即删除）；
     * 连续失败5次直接作废验证码，防止有效期内暴力穷举
     */
    public void verify(String phone, String scene, String code) {
        // 校验验证
        String key = RedisService.SMS_CODE_PREFIX + scene + ":" + phone;
        String cached = redisService.get(key);
        if (cached == null || !cached.equals(code)) {
            // 失败计数：达到上限删除验证码（攻击者需重新触发发送，受图形验证码与限频约束）
            String failKey = RedisService.SMS_CODE_FAIL_PREFIX + scene + ":" + phone;
            long fails = redisService.increment(failKey, codeExpireSeconds);
            if (fails >= MAX_VERIFY_FAIL) {
                redisService.delete(key);
                redisService.delete(failKey);
                throw new BusinessException(ResultCode.SMS_CODE_ERROR, "尝试次数过多，验证码已作废，请重新获取");
            }
            throw new BusinessException(ResultCode.SMS_CODE_ERROR);
        }
        redisService.delete(key);
        redisService.delete(RedisService.SMS_CODE_FAIL_PREFIX + scene + ":" + phone);
    }
}