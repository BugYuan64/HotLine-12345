package org.tjetc.backend.service.common;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 阿里云短信发送器（dysmsapi SendSms，V1 RPC 签名，无 SDK 依赖）：
 * - 配置 app.sms.sender=aliyun 时启用（application.yaml / 环境变量 ALIYUN_SMS_* 填实际 AK/签名/模板）；
 * - 签名/模板需在阿里云控制台申请且配套使用（测试签名与测试模板需成对，否则 isv.SMS_TEST_SIGN_TEMPLATE_LIMIT）；
 * - 发送失败抛异常，由 SmsService 转换为 THIRD_PARTY_ERROR；
 * - 验证码不在日志明文输出（防泄露）。
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "app.sms.sender", havingValue = "aliyun")
public class AliyunSmsSender implements SmsSender {

    @Value("${app.sms.aliyun.access-key:}")
    private String accessKey;

    @Value("${app.sms.aliyun.secret-key:}")
    private String secretKey;

    @Value("${app.sms.aliyun.sign-name:}")
    private String signName;

    @Value("${app.sms.aliyun.template-code:}")
    private String templateCode;

    @Override
    public void send(String phone, String code) {
        if (accessKey == null || accessKey.isBlank()) {
            throw new IllegalStateException("阿里云短信未配置（app.sms.aliyun.access-key 为空）");
        }
        // 公共参数 + 业务参数（TreeMap 按字典序参与签名）
        Map<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", accessKey);
        params.put("Action", "SendSms");
        params.put("Format", "JSON");
        params.put("PhoneNumbers", phone);
        params.put("RegionId", "cn-hangzhou");
        params.put("SignName", signName);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("TemplateCode", templateCode);
        params.put("TemplateParam", "{\"code\":\"" + code + "\"}");
        params.put("Timestamp", LocalDateTime.now(ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
        params.put("Version", "2017-05-25");

        // V1 RPC 签名：规范化查询串 → stringToSign = GET&%2F&urlencode(规范化串)
        String canonical = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + popEncode(e.getValue()))
                .collect(Collectors.joining("&"));
        String stringToSign = "GET&" + popEncode("/") + "&" + popEncode(canonical);
        String signature = java.util.Base64.getEncoder().encodeToString(
                SecureUtil.hmacSha1((secretKey + "&").getBytes(StandardCharsets.UTF_8))
                        .digest(stringToSign.getBytes(StandardCharsets.UTF_8)));
        params.put("Signature", signature);

        String url = "https://dysmsapi.aliyuncs.com/?" + params.entrySet().stream()
                .map(e -> e.getKey() + "=" + popEncode(e.getValue()))
                .collect(Collectors.joining("&"));
        String resp = HttpUtil.get(url, 10000);
        if (resp == null || !resp.contains("\"Code\":\"OK\"")) {
            log.warn("阿里云短信发送失败：phone={} resp={}", phone, resp);
            throw new IllegalStateException("阿里云短信发送失败：" + resp);
        }
        log.info("阿里云短信发送成功：{}", phone);
    }

    /** POP 编码：RFC3986（+ → %20，* → %2A，%7E → ~） */
    private static String popEncode(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
}
