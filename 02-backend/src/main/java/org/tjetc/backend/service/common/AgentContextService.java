package org.tjetc.backend.service.common;

import tools.jackson.databind.ObjectMapper;
import cn.hutool.crypto.SecureUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.ResultCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 助手上下文令牌服务（智能助手升级：四端 AI 助手工具调用）：
 * - 签发：Java 发起聊天问答时，把当前登录用户上下文签发为短时效令牌（agent_context）随请求发给 Python；
 * - 回传：Python AI 助手工具回调 Java /internal/agent/* 查询业务数据时原样携带该令牌，
 *   本服务校验签名与有效期后还原 LoginUser，数据权限仍由各端既有 service 按 LoginUser 过滤；
 * - 令牌格式：base64url(JSON payload) + "." + HmacSHA256(payload, signSecret)
 *   payload: {"exp":过期秒级时间戳,"uid":用户ID,"ut":用户类型,"rg":辖区,"jn":工号,"ph":手机号,"sc":作用域}
 * - 另提供内网签名校验（X-Timestamp/X-Internal-Sign），与 ChatProxyService/AiServiceClient 同款算法互逆。
 */
@Service
public class AgentContextService {

    /** 令牌有效期（秒）：单轮问答内有效，超期后工具调用需重新发起问答 */
    private static final long TTL_SECONDS = 600;
    /** 内网签名容差（秒）：与 Python SIGN_TOLERANCE_SECONDS 保持一致 */
    private static final long SIGN_TOLERANCE_SECONDS = 300;

    private final String signSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentContextService(@Value("${app.ai.sign-secret}") String signSecret) {
        this.signSecret = signSecret;
    }

    /** 签发上下文令牌；user 为 null（匿名）时返回 null，Python 侧退化为纯知识问答 */
    public String issue(LoginUser user, String scope) {
        if (user == null) {
            return null;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("exp", System.currentTimeMillis() / 1000 + TTL_SECONDS);
            payload.put("uid", user.userId());
            payload.put("ut", user.userType());
            payload.put("rg", user.region());
            payload.put("rgc", user.regionCode());
            payload.put("jn", user.jobNo());
            payload.put("ph", user.phone());
            payload.put("sc", scope);
            String json = objectMapper.writeValueAsString(payload);
            String body = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            String sign = SecureUtil.hmacSha256(signSecret).digestHex(body);
            return body + "." + sign;
        } catch (Exception e) {
            throw new IllegalStateException("AI 助手上下文令牌签发失败", e);
        }
    }

    /** 校验并还原上下文令牌为 LoginUser；签名不合法或已过期抛 40101 */
    public LoginUser verify(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "AI 助手上下文令牌缺失或不合法");
        }
        int idx = token.lastIndexOf('.');
        if (idx <= 0 || idx == token.length() - 1) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "AI 助手上下文令牌格式不合法");
        }
        String body = token.substring(0, idx);
        String sign = token.substring(idx + 1);
        String expected = SecureUtil.hmacSha256(signSecret).digestHex(body);
        if (!expected.equals(sign)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "AI 助手上下文令牌签名校验失败");
        }
        Map<?, ?> payload;
        try {
            String json = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
            payload = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "AI 助手上下文令牌解析失败");
        }
        Object exp = payload.get("exp");
        if (exp == null || System.currentTimeMillis() / 1000 > Long.parseLong(String.valueOf(exp))) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "AI 助手上下文令牌已过期，请重新提问");
        }
        return new LoginUser(
                payload.get("uid") == null ? null : Long.valueOf(String.valueOf(payload.get("uid"))),
                (String) payload.get("ut"),
                (String) payload.get("rg"),
                (String) payload.get("rgc"),
                (String) payload.get("jn"),
                (String) payload.get("ph"));
    }

    /** 校验 Python 侧内网签名（X-Timestamp/X-Internal-Sign），确认调用方为 AI 服务本体 */
    public void verifyInternalSign(String timestamp, String sign) {
        if (timestamp == null || timestamp.isBlank() || sign == null || sign.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "缺少内网签名");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "内网签名时间戳不合法");
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > SIGN_TOLERANCE_SECONDS) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "内网签名已过期");
        }
        String expected = SecureUtil.hmacSha256(signSecret).digestHex(timestamp);
        if (!expected.equals(sign)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "内网签名校验失败");
        }
    }
}
