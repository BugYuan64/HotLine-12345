package org.tjetc.backend.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.tjetc.backend.dto.citizen.ChatDocRequest;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.citizen.ChatStreamRequest;
import org.tjetc.backend.service.citizen.ChatProxyService;

import java.util.Map;

/**
 * 管理端智能助手（RAG 流式问答，独立知识库 admin）：
 * - POST /api/v1/admin/chat/stream：SSE 流式接口，JWT token 鉴权（拦截器校验管理员身份，未加入白名单）
 * - POST /api/v1/admin/chat/upload_doc：管理员知识库文档入库（维护独立向量库 chat_admin_idx）
 * - 固定使用 scope=admin 知识库（平台管理/账号审核/工单流转/预判预警配置等管理员高频问题），
 *   与市民端/部门端/街道办端完全隔离；RAG 检索、LangChain 逻辑均在 Python 服务实现，Java 仅鉴权 + 代理转发
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/chat")
public class AdminChatController {

    /** 管理员端知识库作用域（与 Python chat_admin_idx 索引对应） */
    private static final String SCOPE = "admin";

    private final ChatProxyService chatProxyService;

    /** 管理端智能助手流式问答（SSE）：代理 Python RAG 服务，锁定 admin 知识库 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatStreamRequest request) {
        // 强制锁定本端知识库作用域（不信任前端传值），前端即使传错也归位到 admin
        ChatStreamRequest scoped = new ChatStreamRequest(
                request.question(), request.history(), SCOPE);
        // 携带登录用户上下文（签发 AI 助手上下文令牌，供工具调用回查权限内业务数据）
        return chatProxyService.stream(scoped, UserContext.get());
    }

    /** 管理员知识库文档入库：转发 Python /ai/v1/chat/upload_doc（写入 chat_admin_idx 独立向量库） */
    @PostMapping("/upload_doc")
    public Map<String, Object> uploadDoc(@Valid @RequestBody ChatDocRequest request) {
        // 强制锁定 admin 作用域，管理员文档只进管理员知识库
        ChatDocRequest scoped = new ChatDocRequest(
                request.title(), request.content(), request.category(), SCOPE);
        return chatProxyService.uploadDoc(scoped);
    }
}