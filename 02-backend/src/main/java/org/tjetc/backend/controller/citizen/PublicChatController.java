package org.tjetc.backend.controller.citizen;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.citizen.ChatStreamRequest;
import org.tjetc.backend.service.citizen.ChatProxyService;

/**
 * 公众端（市民端）智能助手（RAG 流式问答，独立知识库 citizen）：
 * - POST /api/v1/public/chat/stream：SSE 流式接口，JWT token 鉴权（不加入白名单）
 * - Java 只做 token 鉴权 + SSE 代理转发；向量检索 / prompt 组装 / LLM 生成全部在 Python 服务
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/public/chat")
public class PublicChatController {

    private static final String SCOPE = "citizen";

    private final ChatProxyService chatProxyService;

    /** 智能助手流式问答（SSE）：WebClient 代理 Python RAG 服务逐块输出 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatStreamRequest request) {
        // 强制锁定本端知识库作用域（不信任前端传值），前端即使传错也归位到 citizen
        ChatStreamRequest scoped = new ChatStreamRequest(
                request.question(), request.history(), SCOPE);
        // 携带登录用户上下文（签发 AI 助手上下文令牌，供工具调用回查权限内业务数据）
        return chatProxyService.stream(scoped, UserContext.get());
    }
}
