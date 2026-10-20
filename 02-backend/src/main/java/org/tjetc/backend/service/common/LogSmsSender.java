package org.tjetc.backend.service.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 开发环境短信发送器：仅打印验证码日志（配置 app.sms.sender=log-sender）
 * 生产环境替换为真实短信服务商实现（如阿里云短信）
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "app.sms.sender", havingValue = "log-sender", matchIfMissing = true)
public class LogSmsSender implements SmsSender {

    @Override
    public void send(String phone, String code) {
        // 发送数据
        log.info("[开发模式短信] 向 {} 发送验证码：{}", phone, code);
    }
}