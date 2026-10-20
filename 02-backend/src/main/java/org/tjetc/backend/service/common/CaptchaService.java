package org.tjetc.backend.service.common;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.CircleCaptcha;
import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.ResultCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图形验证码服务（登录防人机）：
 * - Hutool 生成 4 位字母数字验证码图片（base64 返回，无需落盘）
 * - 答案存 Redis（默认 2 分钟过期），一次性校验：无论对错即失效，防止机器爆破
 */
@Service
@RequiredArgsConstructor
public class CaptchaService {

    @Value("${app.captcha.expire-seconds:120}")
    private long expireSeconds;

    private final RedisService redisService;

    /**
     * 生成图形验证码，返回 {captcha_id, image_base64, expire_seconds}
     */
    public Map<String, Object> generate() {
        // 4位验证码，120x40 像素，6 条干扰线
        CircleCaptcha captcha = CaptchaUtil.createCircleCaptcha(120, 40, 4, 6);
        String captchaId = IdUtil.fastSimpleUUID();
        redisService.set(RedisService.CAPTCHA_PREFIX + captchaId, captcha.getCode(), expireSeconds);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("captcha_id", captchaId);
        data.put("image_base64", captcha.getImageBase64Data());
        data.put("expire_seconds", expireSeconds);
        return data;
    }

    /**
     * 校验图形验证码（默认一次性：无论对错即删除，防爆破）。
     * 保留本方法不变，避免影响其他模块（管理端/工作人员登录、发邮箱码等）的既有调用。
     */
    public void verify(String captchaId, String captchaCode) {
        verify(captchaId, captchaCode, true);
    }

    /**
     * 校验图形验证码（捎带是否销毁开关）：
     * - consume=true：校验通过后删除（用一次即作废）
     * - consume=false：仅校验不删除（供后续步骤例如登录再次复用同一验证码）
     * 校验错误时无论 consume 取值均删除，防止爆破。
     */
    public void verify(String captchaId, String captchaCode, boolean consume) {
        // 校验图形验证码
        if (captchaId == null || captchaCode == null || captchaCode.isBlank()) {
            throw new BusinessException(ResultCode.SMS_CODE_ERROR, "图形验证码错误或已过期，请刷新后重试");
        }
        String key = RedisService.CAPTCHA_PREFIX + captchaId;
        String cached = redisService.get(key);
        if (cached == null || !cached.equalsIgnoreCase(captchaCode.trim())) {
            redisService.delete(key);
            throw new BusinessException(ResultCode.SMS_CODE_ERROR, "图形验证码错误或已过期，请刷新后重试");
        }
        if (consume) {
            redisService.delete(key);
        }
    }
}
