package org.tjetc.backend.service.citizen;

import cn.hutool.crypto.SecureUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.dto.citizen.ChatDocRequest;
import org.tjetc.backend.dto.citizen.ChatStreamRequest;
import org.tjetc.backend.service.common.AgentContextService;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

/**
 * 智能助手代理服务（Java 只做鉴权 + 代理转发，RAG 全部在 Python 服务实现）：
 * - WebClient 以 SSE 流式调用 Python /ai/v1/chat/stream，逐事件转发给浏览器 SseEmitter
 * - 请求头携带内网签名 X-Internal-Sign: HMAC-SHA256(timestamp, secret)
 * - 知识文档入库同步转发（管理员维护知识库用）
 */
@Slf4j
@Service
public class ChatProxyService {

    /** Python RAG 服务 SSE 转发的整体读超时（单次问答最长生成时间） */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(3);

    private final WebClient webClient;
    private final String signSecret;
    private final AgentContextService agentContextService;

    public ChatProxyService(@Value("${app.ai.base-url}") String baseUrl,
                            @Value("${app.ai.sign-secret}") String signSecret,
                            AgentContextService agentContextService) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.signSecret = signSecret;
        this.agentContextService = agentContextService;
    }

    /** 生成内网签名请求头（与 AiServiceClient 同款算法） */
    private String[] signHeaders() {
        long timestamp = System.currentTimeMillis() / 1000;
        String sign = SecureUtil.hmacSha256(signSecret).digestHex(String.valueOf(timestamp));
        return new String[]{String.valueOf(timestamp), sign};
    }

    /**
     * SSE 流式问答代理：调 Python /ai/v1/chat/stream，将每个 SSE 事件原样转发给前端
     * SSE 事件协议（data 为 JSON 字符串）：
     * sources（知识来源）→ delta（回答增量，多条）→ done（结束）/ error（异常）
     */
    public SseEmitter stream(ChatStreamRequest req) {
        return stream(req, null);
    }

    /**
     * SSE 流式问答代理（AI 助手升级）：携带当前登录用户上下文
     * 为登录用户签发短时效上下文令牌（agent_context）随请求发给 Python，
     * AI 助手工具回调 /internal/agent/* 时回传该令牌以登录用户身份查询业务数据（权限由 Java 侧过滤）。
     */
    public SseEmitter stream(ChatStreamRequest req, LoginUser user) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 永不超时（超时由 STREAM_TIMEOUT 控制）
        String[] sign = signHeaders();
        // 已带令牌则透传；否则为当前登录用户签发（匿名/重复签发防御）
        ChatStreamRequest signed = req;
        if (user != null && (req.agentContext() == null || req.agentContext().isBlank())) {
            signed = new ChatStreamRequest(req.question(), req.history(), req.scope(),
                    agentContextService.issue(user, req.scope()));
        }

        Flux<ServerSentEvent<String>> flux = webClient.post()
                .uri("/ai/v1/chat/stream")
                .header("X-Internal-Sign", sign[1])
                .header("X-Timestamp", sign[0])
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(signed)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { })
                .timeout(STREAM_TIMEOUT);

        flux.subscribe(
                // 逐事件转发：把 Python SSE 的 data JSON 原样推给浏览器
                event -> {
                    String data = event.data();
                    if (data == null || data.isEmpty()) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event().data(data));
                    } catch (Exception e) {
                        // 浏览器断开等发送失败：结束订阅，Python 侧流会随之取消
                        log.debug("SSE 转发中断（前端已断开）：{}", e.getMessage());
                    }
                },
                err -> {
                    log.warn("智能助手流式调用失败：{}", err.getMessage());
                    // 异常时向前端推送 error 事件后收尾（降级话术由前端兜底）
                    try {
                        emitter.send(SseEmitter.event().data(
                                "{\"type\":\"error\",\"message\":\"智能助手服务暂时不可用，请稍后再试\"}"));
                        emitter.complete();
                    } catch (Exception ignored) {
                        emitter.completeWithError(err);
                    }
                },
                emitter::complete
        );
        return emitter;
    }

    /** 知识文档入库代理（管理员维护）：同步转发 Python /ai/v1/chat/upload_doc */
    public Map<String, Object> uploadDoc(ChatDocRequest req) {
        String[] sign = signHeaders();
        Map<String, Object> result = webClient.post()
                .uri("/ai/v1/chat/upload_doc")
                .header("X-Internal-Sign", sign[1])
                .header("X-Timestamp", sign[0])
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { })
                .block(Duration.ofSeconds(30));
        if (result == null) {
            throw new IllegalStateException("知识库服务无响应");
        }
        return result;
    }
}
