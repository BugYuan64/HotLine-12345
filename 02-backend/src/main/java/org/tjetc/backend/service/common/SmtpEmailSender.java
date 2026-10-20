package org.tjetc.backend.service.common;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.mail.JakartaMailUtil;
import cn.hutool.extra.mail.MailAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * QQ 邮箱 SMTP 发送器（注册邮箱验证码）：
 * - 账号配置留白（app.mail.username/password 未填写）时仅打印验证码日志，便于开发调试
 * - 配置填写后经 smtp.qq.com 真实发信，失败抛出异常由上层转换为 50003
 */
@Slf4j
@Component
public class SmtpEmailSender implements EmailSender {

    @Value("${app.mail.host:smtp.qq.com}")
    private String host;

    @Value("${app.mail.port:465}")
    private int port;

    @Value("${app.mail.ssl-enabled:true}")
    private boolean sslEnabled;

    @Value("${app.mail.from:}")
    private String from;

    @Value("${app.mail.username:}")
    private String username;

    @Value("${app.mail.password:}")
    private String password;

    @Value("${app.mail.connect-timeout-ms:10000}")
    private long connectTimeoutMs;

    @Value("${app.mail.read-timeout-ms:10000}")
    private long readTimeoutMs;

    @Override
    public void send(String to, String code) {
        // 账号配置留白：开发模式仅打印验证码日志
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            log.warn("[开发模式邮箱] app.mail.username/password 未配置，仅打印日志。向 {} 发送验证码：{}", to, code);
            return;
        }
        MailAccount account = new MailAccount()
                .setHost(host)
                .setPort(port)
                .setAuth(true)
                .setSslEnable(sslEnabled)
                .setFrom(StrUtil.blankToDefault(from, username))
                .setUser(username)
                .setPass(password)
                .setCharset(StandardCharsets.UTF_8);
        account.setConnectionTimeout(connectTimeoutMs);
        account.setTimeout(readTimeoutMs);
        String content = StrUtil.format(
                "<div style=\"font-size:14px;color:#333\">" +
                        "<p>您好：</p>" +
                        "<p>您正在 12345政务热线系统 进行身份验证（登录/注册），本次验证码为：</p>" +
                        "<p style=\"font-size:26px;font-weight:bold;color:#1d5c9b;letter-spacing:6px\">{}</p>" +
                        "<p>验证码 5 分钟内有效，请勿泄露给他人。如非本人操作请忽略本邮件。</p>" +
                        "</div>", code);
        // 经 QQ 邮箱 SMTP 真实发信（isHtml=true）
        JakartaMailUtil.send(account, to, "【12345政务热线】邮箱验证码", content, true);
        log.info("邮箱验证码已发送至 {}", to);
    }
}
