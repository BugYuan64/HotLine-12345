package org.tjetc.backend.service.common;

/**
 * 邮箱发送器（注册邮箱验证码功能）：
 * 默认实现 SmtpEmailSender 经 QQ 邮箱 SMTP 发信（配置 app.mail.*）；
 * 发送失败抛出异常，由上层转换为 50003 第三方服务调用失败
 */
public interface EmailSender {

    /**
     * @param to   收件邮箱
     * @param code 6 位数字验证码
     */
    void send(String to, String code);
}
