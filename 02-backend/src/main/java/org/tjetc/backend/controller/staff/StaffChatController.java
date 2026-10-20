package org.tjetc.backend.controller.staff;

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
 * 部门端智能助手（RAG 流式问答，独立知识库 staff）：
 * - POST /api/v1/staff/chat/stream：SSE 流式接口，JWT token 鉴权（拦截器校验部门角色）
 * - 固定使用 scope=staff 知识库（工单处置/办理规范内参知识），与市民端/街道办端完全隔离
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/staff/chat")
public class StaffChatController {

    private static final String SCOPE = "staff";

    private final ChatProxyService chatProxyService;

    /** 部门端智能助手流式问答（SSE）：代理 Python RAG 服务，锁定 staff 知识库 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatStreamRequest request) {
        // 强制锁定本端知识库作用域（不信任前端传值），前端即使传错也归位到 staff
        ChatStreamRequest scoped = new ChatStreamRequest(
                request.question(), request.history(), SCOPE);
        // 携带登录用户上下文（签发 AI 助手上下文令牌，供工具调用回查权限内业务数据）
        return chatProxyService.stream(scoped, UserContext.get());
    }
}