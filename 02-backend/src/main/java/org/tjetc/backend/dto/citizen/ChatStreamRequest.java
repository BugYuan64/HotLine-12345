package org.tjetc.backend.dto.citizen;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 智能助手流式问答请求（四端共用）：当前问题 + 多轮对话历史 + 知识库作用域 + AI 助手上下文令牌
 * 字段名为全单词（question/history/role/content/scope），与 Python Pydantic ChatStreamRequest 直接对应
 * scope：citizen（市民端）/ staff（部门端）/ street（街道办端）/ admin（管理端），决定使用哪个独立知识库
 * agentContext：AI 助手上下文令牌（AgentContextService 签发，Python 工具回调 /internal/agent/* 时回传），
 *               仅内部传递，前端无需感知；为空时 Python 退化为纯知识问答（不启用工具）
 */
public record ChatStreamRequest(
        @NotBlank(message = "问题内容不能为空")
        @Size(max = 500, message = "问题内容不能超过500字")
        String question,
        @Valid
        @Size(max = 20, message = "对话历史过长")
        List<ChatHistoryItem> history,
        @Size(max = 20, message = "知识库作用域不合法")
        String scope,
        @Size(max = 4096, message = "上下文令牌不合法")
        String agentContext) {

    /** 兼容旧三参构造（无上下文令牌） */
    public ChatStreamRequest(String question, List<ChatHistoryItem> history, String scope) {
        this(question, history, scope, null);
    }

    /** 单条对话历史（多轮上下文）。
     *  上限 8000 字符：history 携带的是上一轮 AI 完整回答（常超 500 字），
     *  早期 500 上限导致多轮对话第二条起必 400，放宽为 8000（条数仍限 20 条，防资源耗用不变） */
    public record ChatHistoryItem(
            String role,
            @Size(max = 8000, message = "单条历史不能超过8000字")
            String content) {
    }
}
