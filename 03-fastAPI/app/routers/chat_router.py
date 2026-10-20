"""智能助手 RAG 接口（/ai/v1/chat/*）：仅供 Java 业务服务内网调用（签名校验）

- POST /ai/v1/chat/stream     SSE 流式问答：问题 → 向量+BM25 混合检索 → 组装 prompt → LLM 流式生成
- POST /ai/v1/chat/upload_doc 知识文档入库（文本导入 Redis Stack 向量库）
- scope 参数（citizen/staff/street）决定使用哪个端独立知识库（一个端一个知识库）
"""
import asyncio
import json
import re

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse
from loguru import logger

from pydantic_ai import Agent
from pydantic_ai.messages import (
    ModelRequest,
    ModelResponse,
    PartDeltaEvent,
    PartStartEvent,
    TextPart,
    TextPartDelta,
    UserPromptPart,
)

from app.routers.auth import verify_internal
from app.config import AGENT_ENABLED, DASHSCOPE_API_KEY, SUGGEST_MODEL
from app.core.ai.chat_kb import ChatKnowledgeBase
from app.core.ai.llm_client import LlmUnavailable, chat_json, chat_stream
from app.core.ai.assistant import AgentDeps, get_agent
from app.schemas import ChatDocRequest, ChatDocResponse, ChatStreamRequest

router = APIRouter(prefix="/ai/v1/chat", dependencies=[Depends(verify_internal)])

# 各端人设：严格依据对应端知识库回答
SYSTEM_PROMPT_BY_SCOPE = {
    "citizen": (
        "你是「12345政务服务热线」的智能助手小政。"
        "请严格依据【知识库】中的内容回答市民问题，用简洁、友好、口语化的中文回答；"
        "若知识库不足以回答，请如实说明并建议：拨打12345热线人工咨询，或在本平台「提交诉求」页面在线登记。"
        "重要规则：当市民询问本平台（实体）暂未提供的功能或业务时，请直接明确回答「该功能正在开发中，敬请期待」，"
        "不得编造该功能已上线，不得含糊引导，也不得声称系统支持不存在的功能。"
        "不要编造知识库以外的政策信息；回答控制在300字以内。"
    ),
    "staff": (
        "你是「12345政务热线」部门处置端的智能工作助手。"
        "请严格依据【内部知识库】中的工单处置规范回答部门工作人员的问题（签收/办理时限/退回/延期/回访等），"
        "用专业、简洁的中文回答；若知识库没有相关内容，请建议咨询平台管理员。"
        "重要规则：当工作人员询问系统暂未提供的功能或操作时，请直接明确回答「该功能正在开发中，敬请期待」，"
        "不得编造功能已上线或含糊其辞。"
        "禁止回答涉及市民个人隐私的信息；不要编造知识库以外的办理规则；回答控制在300字以内。"
    ),
    "street": (
        "你是「12345政务热线」街道办端的智能工作助手。"
        "请严格依据【内部知识库】中的街道属地责任与办理规范回答街道办工作人员的问题"
        "（属地范围/签收/网格联动/市政设施上报/应急响应等），用专业、简洁的中文回答；"
        "若知识库没有相关内容，请建议咨询平台管理员；不要编造知识库以外的规则。"
        "重要规则：当工作人员询问系统暂未提供的功能或操作时，请直接明确回答「该功能正在开发中，敬请期待」，"
        "不得编造功能已上线或含糊其辞。回答控制在300字以内。"
    ),
    "admin": (
        "你是「12345政务热线」平台管理系统管理员端的工作助手。"
        "请严格依据【内部知识库】中的平台管理与运营配置规范回答管理员的问题"
        "（账号审核/用户管理/诉求拆分与建单/工单流转/预判配置/预警规则/分类体系/审计日志/知识库维护等），"
        "用专业、简洁的中文回答；若知识库没有相关内容，请如实说明并建议结合系统页面操作确认。"
        "重要规则：当管理员询问系统暂未提供的功能或操作时，请直接明确回答「该功能正在开发中，敬请期待」，"
        "不得编造功能已上线、含糊引导或声称系统支持不存在的功能；不要编造知识库以外的配置与规则；回答控制在300字以内。"
    ),
}


def _sse(payload: dict) -> str:
    """格式化一条 SSE 事件（data: JSON\n\n）"""
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _build_messages(question: str, docs: list, history: list, scope: str) -> list:
    """组装大模型消息：系统人设 + 知识库上下文 + 最近多轮历史 + 当前问题"""
    kb_text = "\n\n".join(
        f"【{i}】{d['title']}：{d['content']}" for i, d in enumerate(docs, start=1)
    ) or "（知识库未检索到相关内容）"

    system = SYSTEM_PROMPT_BY_SCOPE.get(scope, SYSTEM_PROMPT_BY_SCOPE["citizen"])
    messages = [{"role": "system", "content": system}]
    # 最近 N 条历史（CHAT_MAX_HISTORY），截断防 prompt 超长
    for item in history[-6:]:
        role = item.role if item.role in ("user", "assistant") else "user"
        messages.append({"role": role, "content": item.content[:500]})
    messages.append({"role": "user", "content": f"【知识库】\n{kb_text}\n\n【市民问题】{question}"})
    return messages


def _fallback_text(docs: list, question: str) -> str:
    """降级回答（LLM 不可用）：知识库有命中时引用原文；未命中时提示功能开发中"""
    if docs:
        top = docs[0]
        return f"已为您找到以下信息：\n\n【{top['title']}】\n{top['content']}\n\n如仍有疑问，请拨打12345热线人工咨询。"
    return "抱歉，您咨询的功能或业务目前尚未上线，该功能正在开发中，敬请期待。如需帮助，请拨打12345热线人工咨询。"



# ------------------------------------------------------------------ 「试试查看全部」能力清单
# 前端推荐问题区「试试查看全部」按钮固定发送该问题，后端拦截后按端流式输出能力清单（避免模型编造）
ALL_QUESTIONS_TRIGGER = "查看全部推荐问题"

_CAPABILITY_BY_SCOPE = {
    "citizen": (
        "您好！以下是我能为您做的全部事情：\n\n"
        "【可咨询的常见知识】\n"
        "1. 12345热线受理范围（8大分类）与不受理事项\n"
        "2. 在线提交诉求的流程与填写技巧（怎么写处理更快）\n"
        "3. 诉求提交后AI如何处理（拆分/分类/同源归并）\n"
        "4. 诉求编号规则（AP父诉求/AS子诉求/WO工单）\n"
        "5. 办理进度查询方式\n"
        "6. 处置结果评价规则与申请重新处理\n"
        "7. 敏感诉求的处理说明\n"
        "8. 紧急情况求助渠道（110/119/120/122）\n\n"
        "【可调用的工具（直接对我说）】\n"
        "1. 查询我的诉求列表 —— 试试说：我提交过哪些诉求？\n"
        "2. 查询诉求办理进度与流转时间线 —— 试试说：帮我查一下诉求xxx的办理进度\n"
        "3. 诉求描述智能补全 —— 试试说：帮我补全一下诉求描述"
    ),
    "staff": (
        "您好！以下是我能为您做的全部事情：\n\n"
        "【可咨询的常见知识】\n"
        "1. 部门工单签收要求与时限\n"
        "2. 工单办理时限（一般/复杂事项）\n"
        "3. 工单等级与处置优先级（一/二/三级）\n"
        "4. 部门下派街道办流程\n"
        "5. 工单退回与延期流程\n"
        "6. 低置信度工单人工复核\n"
        "7. 处置结果填写规范与市民评价规则\n"
        "8. 预警工单应急处置联动\n\n"
        "【可调用的工具（直接对我说）】\n"
        "1. 查询本部门工单列表（工单号/关键词/状态/时间过滤）—— 试试说：帮我查一下最近的工单\n"
        "2. 查询工单详情 —— 试试说：查一下工单WO20260908017的详情\n"
        "3. 查询工单流转时间线 —— 试试说：这张工单流转到哪一步了？\n"
        "4. 查询部门工作台汇总 —— 试试说：看看我们部门的工作台汇总"
    ),
    "street": (
        "您好！以下是我能为您做的全部事情：\n\n"
        "【可咨询的常见知识】\n"
        "1. 街道办属地责任范围\n"
        "2. 街道工单签收要求\n"
        "3. 办理时限与办结流程\n"
        "4. 街道办退回部门规则（上限2次）\n"
        "5. 预警分发响应要求\n"
        "6. 社区网格联动处置\n"
        "7. 市政设施问题上报流程\n"
        "8. 应急事件响应要求\n\n"
        "【可调用的工具（直接对我说）】\n"
        "1. 查询本街道工单列表（工单号/关键词/状态/时间过滤）—— 试试说：查一下分到我们街道的工单\n"
        "2. 查询工单详情与流转时间线 —— 试试说：这张工单处置到什么环节了？\n"
        "3. 查询分发到本街道的预警 —— 试试说：有哪些预警分发到我们街道办？"
    ),
    "admin": (
        "您好！以下是我能为您做的全部事情：\n\n"
        "【可咨询的常见知识】\n"
        "1. 账号审核流程与工号规则\n"
        "2. 父/子诉求与AI自动拆分机制\n"
        "3. 工单全生命周期状态机与分派/退办规则\n"
        "4. 工单等级规则与终审归档\n"
        "5. 市民评价与重开审批规则\n"
        "6. 预判功能与参数配置\n"
        "7. 预警规则维护与扫描机制\n"
        "8. 分类体系维护\n"
        "9. 系统审计（系统/部门/街道办三视图）与知识库管理\n\n"
        "【可调用的工具（直接对我说）】\n"
        "1. 查询全平台工单（多条件过滤）/ 工单全链路视图\n"
        "2. 查询父诉求列表与子诉求明细\n"
        "3. 查询系统/部门/街道审计日志\n"
        "4. 查询预警记录与预警规则\n"
        "5. 查询预判热点/趋势/准确率\n"
        "6. 查询账号列表与待审核账号\n"
        "7. 查询全局待办汇总\n"
        "8. 查询自动处理开关状态（拆分/建单/派单）\n"
        "9. 维护知识库文档（上传入库）\n"
        "10. 手动触发预警扫描 / 触发诉求预判"
    ),
}


async def _capability_generate(scope: str):
    """「试试查看全部」：按端流式输出能力清单（分片+微延迟，打字机效果）"""
    text = _CAPABILITY_BY_SCOPE.get(scope) or _CAPABILITY_BY_SCOPE["citizen"]
    for i in range(0, len(text), 36):
        yield _sse({"type": "delta", "content": text[i:i + 36]})
        await asyncio.sleep(0.02)
    yield _sse({"type": "done"})


# ------------------------------------------------------------------ 文本分片流式输出（大分片切分，保证打字机体验）
async def _emit_text(content: str):
    """把文本切为较小分片流式输出；超过 48 字符的大分片（非流式响应）再按 40 字符切分并微延迟"""
    if not content:
        return
    if len(content) <= 48:
        yield _sse({"type": "delta", "content": content})
        return
    for i in range(0, len(content), 40):
        yield _sse({"type": "delta", "content": content[i:i + 40]})
        await asyncio.sleep(0.03)


# ------------------------------------------------------------------ 动态衔接建议生成（前端建议栏数据源）
_SUGGEST_SYSTEM = (
    "你是政务热线智能助手的追问建议生成器。根据用户问题与助手回答，"
    "生成 2-3 条用户最可能想继续追问的问题。"
    '只输出JSON：{"suggestions":["问题1","问题2"]}。要求：每条不超过16字；'
    "是完整可直接发送的中文问句；围绕回答中提到的数据/编号/状态/流程自然延伸"
    "（如查详情、看进展、了解同类情况、下一步操作）；不要重复已回答的内容。"
)


async def _gen_suggestions(question: str, answer: str) -> list:
    """按本轮问答生成衔接建议（done 后异步执行，失败/超时走本地兜底）。

    前端（四端 Chat.vue / ChatFloatBall.vue）监听 suggestions 事件渲染输入框上方建议胶囊；
    使用轻量专用模型 SUGGEST_MODEL 并关闭思考模式（qwen3 非流式调用先思考再输出，
    旗舰模型极易超过请求级超时导致建议栏长期空白）；
    chat_json 为同步 openai 调用，放 worker 线程执行并设 8s 请求级超时
    （to_thread 无法中途取消线程，超时必须由 openai 请求自身触发）。
    """
    if not DASHSCOPE_API_KEY:
        return _fallback_suggestions(question, answer)
    user = f"用户问题：{question[:300]}\n\n助手回答：{answer[:600]}"
    try:
        data = await asyncio.to_thread(chat_json, _SUGGEST_SYSTEM, user, 8.0,
                                        SUGGEST_MODEL, True)
        items = data.get("suggestions") if isinstance(data, dict) else None
        if not isinstance(items, list):
            return _fallback_suggestions(question, answer)
        # 清洗：去空白、限长、去重、最多 3 条
        cleaned, seen = [], set()
        for s in items:
            t = str(s).strip().strip('"“”')
            if 2 <= len(t) <= 24 and t not in seen:
                seen.add(t)
                cleaned.append(t)
            if len(cleaned) >= 3:
                break
        return cleaned or _fallback_suggestions(question, answer)
    except Exception as e:  # noqa: BLE001
        logger.warning("衔接建议生成失败，使用本地兜底：{}", e)
        return _fallback_suggestions(question, answer)


def _fallback_suggestions(question: str, answer: str) -> list:
    """本地兜底建议（LLM 失败/超时/返回空时）：从问答文本提取业务编号生成模板化
    追问建议，不足 3 条补通用问题，保证每次回答后建议栏始终有内容"""
    text = f"{question}\n{answer}"
    nos = re.findall(r"(?:AP|AS|WO)\d{6,}", text)
    out = [f"查看{no}的办理进度" for no in dict.fromkeys(nos)][:3]
    for t in ("怎么查询办理进度？", "处理需要多长时间？", "还有哪些注意事项？"):
        if len(out) >= 3:
            break
        out.append(t)
    return out


# ------------------------------------------------------------------ 多轮历史转 pydantic-ai 消息
def _to_message_history(history: list, question: str) -> list:
    # 前端把「当前这条问题」也放进 history 末端，而 agent.iter(question, ...) 会再把它作为
    # 新的一轮，不去重会导致问题在 message_history 与 user_prompt 中重复出现。去掉匹配末条。
    items = (history or [])[-6:]
    while items:
        tail = items[-1]
        if (tail.role or "user").strip().lower() == "user" and (tail.content or "").strip() == (question or "").strip():
            items = items[:-1]
        else:
            break
    out = []
    for item in items:
        role = (item.role or "user").strip().lower()
        if role == "assistant":
            out.append(ModelResponse(parts=[TextPart(content=item.content[:500])]))
        else:
            # 用户历史消息必须用 UserPromptPart：ModelRequest 的 parts 不接受 TextPart，
            # 否则 pydantic-ai 消息循环的穷尽性断言会抛
            # "Expected code to be unreachable, but got: TextPart(...)"（多轮必崩回退 legacy）
            out.append(ModelRequest(parts=[UserPromptPart(content=item.content[:500])]))
    return out


# ------------------------------------------------------------------ 10.13 智能助手 RAG 流式问答（legacy 兜底）
@router.post("/stream")
def chat_stream_endpoint(req: ChatStreamRequest, request: Request):
    """SSE 流式问答接口（Java 后端 WebClient 代理转发给浏览器前端）

    AI 助手优先：携带用户上下文令牌(agent_context)时走 PydanticAI Agent（带业务工具）；
    未启用/令牌缺失/构建失败时回退原 RAG 纯知识问答，保证既有功能不回归。

    SSE 事件协议（data 为 JSON 行）：
      {"type":"sources",...}    知识来源（仅 legacy RAG 路径）
      {"type":"delta","content":"..."}                   回答文本增量
      {"type":"done"}                                    正常结束
      {"type":"suggestions","suggestions":[...]}         动态衔接建议（done 后异步生成，失败静默跳过）
      {"type":"error","message":"..."}                   异常结束
      （type=tool 事件已停发：工具调用标记不再展示，前端处理器仅作兼容保留）

    特殊问题：「查看全部推荐问题」→ 按端流式输出能力清单（企业知识 + 工具调用）
    """
    scope = req.scope or "citizen"
    kb: ChatKnowledgeBase = getattr(request.app.state, f"chat_kb_{scope}", None)
    if kb is None:
        return StreamingResponse(iter([_sse({
            "type": "error", "message": f"未知知识库作用域: {scope}"
        }) + _sse({"type": "done"})]), media_type="text/event-stream")

    # 「试试查看全部」：拦截固定问题，流式输出该端能力清单（无需知识库/Agent）
    if req.question.strip() == ALL_QUESTIONS_TRIGGER:
        logger.info("输出能力清单 scope={}", scope)
        return StreamingResponse(
            _capability_generate(scope),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "Connection": "keep-alive", "X-Accel-Buffering": "no"},
        )

    logger.info("客服问答 scope={} question={} history_len={} agent_enabled={}",
                scope, req.question[:50], len(req.history), AGENT_ENABLED)

    # 是否启用 AI 助手：总开关 + 上下文令牌 + 大模型密钥；Agent 预构建失败则回退 RAG
    use_agent = AGENT_ENABLED and bool(req.agent_context) and bool(DASHSCOPE_API_KEY)
    if use_agent:
        try:
            get_agent(scope)
        except Exception as e:
            logger.warning("AI 助手构建失败，回退 RAG（scope={}）：{}", scope, e)
            use_agent = False

    async def wrapped():
        # Agent 中途异常且尚未产出任何回答时，回退到 legacy RAG，保证体验不回退
        emitted = False
        try:
            async for payload in _agent_generate(req, scope, kb):
                if payload.startswith("data:") and '"delta"' in payload:
                    emitted = True
                yield payload
        except Exception as e:
            logger.warning("AI 助手回退 RAG（scope={}, emitted={}）：{}", scope, emitted, e)
            if not emitted:
                for payload in _legacy_generate(req, scope, kb):
                    yield payload
                return
            yield _sse({"type": "error", "message": "AI 助手开小差了，请稍后再试"})
        # _agent_generate 正常完成后已自行发送 done/suggestions

    stream = wrapped() if use_agent else _legacy_generate(req, scope, kb)
    return StreamingResponse(
        stream,
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",  # Nginx 反代时关闭缓冲
        },
    )


# ------------------------------------------------------------------ AI 助手生成（含工具调用过程）
async def _agent_generate(req: ChatStreamRequest, scope: str, kb: ChatKnowledgeBase):
    """PydanticAI Agent 流式执行：
    模型请求节点→提取文本增量（含首块 PartStartEvent，保证非流式响应也逐段输出）；
    工具调用节点→仅消费事件流驱动工具执行（不推 tool 展示事件）；
    完成收尾（done 后按本轮问答生成衔接建议再推 suggestions）。"""
    agent = get_agent(scope)
    deps = AgentDeps(scope=scope, context_token=req.agent_context or "", kb=kb)
    history = _to_message_history(req.history, req.question)
    answer_text = []  # 累积本轮回答全文（供衔接建议生成）
    async with agent.iter(req.question, message_history=history, deps=deps) as run:
        async for node in run:
            if Agent.is_model_request_node(node):
                async with node.stream(run.ctx) as stream:
                    async for event in stream:
                        # 新文本段：非流式响应/首块内容在这里整段到达，切分后流式输出
                        if isinstance(event, PartStartEvent):
                            part = getattr(event, "part", None)
                            if isinstance(part, TextPart) and part.content:
                                answer_text.append(part.content)
                                async for chunk in _emit_text(part.content):
                                    yield chunk
                        # 增量文本段
                        elif isinstance(event, PartDeltaEvent) and isinstance(event.delta, TextPartDelta):
                            if event.delta.content_delta:
                                answer_text.append(event.delta.content_delta)
                                async for chunk in _emit_text(event.delta.content_delta):
                                    yield chunk
            elif Agent.is_call_tools_node(node):
                async with node.stream(run.ctx) as stream:
                    async for event in stream:
                        # 工具调用过程不再推 tool 事件（前端已不需要渲染 🔍/✅ 工具标记，
                        # SSE 协议保留 type=tool 定义，前端处理器留着兼容旧事件）
                        pass

    yield _sse({"type": "done"})
    # done 后生成动态衔接建议（前端 fetch 流读到关闭为止、Java 纯透传，均支持 done 后事件；
    # 失败/超时静默跳过不影响主流程，建议栏不显示）
    suggestions = await _gen_suggestions(req.question, "".join(answer_text))
    if suggestions:
        yield _sse({"type": "suggestions", "suggestions": suggestions})


# ------------------------------------------------------------------ legacy RAG 生成（AI 助手关闭/令牌缺失/失败兜底）
def _legacy_generate(req: ChatStreamRequest, scope: str, kb: ChatKnowledgeBase):
    # 1. 混合检索知识库
    try:
        docs = kb.search(req.question)
    except LlmUnavailable:
        docs = []
    except Exception as e:
        logger.warning("知识库检索异常（降级）：{}", e)
        docs = []

    # 2. 先推送知识来源（前端可展示「参考依据」）
    if docs:
        yield _sse({"type": "sources", "sources": [
            {"title": d["title"], "content": d["content"][:120],
             "category": d["category"], "similarity": d.get("similarity")}
            for d in docs
        ]})

    # 3. LLM 流式生成（不可用时降级为知识库原文分块输出）
    try:
        messages = _build_messages(req.question, docs, req.history, scope)
        for chunk in chat_stream(messages):
            yield _sse({"type": "delta", "content": chunk})
    except LlmUnavailable:
        logger.warning("LLM 不可用，客服问答降级输出知识库原文（scope={}）", scope)
        text = _fallback_text(docs, req.question)
        for i in range(0, len(text), 12):
            yield _sse({"type": "delta", "content": text[i:i + 12]})
        yield _sse({"type": "done"})
        return
    except Exception as e:
        logger.error("客服流式生成异常：{}", e)
        yield _sse({"type": "error", "message": "智能助手开小差了，请稍后再试"})
        return

    # 4. 正常收尾
    yield _sse({"type": "done"})


# ------------------------------------------------------------------ 10.13 知识文档入库
@router.post("/upload_doc", response_model=ChatDocResponse)
def upload_doc_endpoint(req: ChatDocRequest, request: Request):
    """知识文档入库：向量化后写入对应端知识库（一个端一个知识库）"""
    scope = req.scope or "citizen"
    kb: ChatKnowledgeBase = getattr(request.app.state, f"chat_kb_{scope}", None)
    if kb is None:
        raise RuntimeError(f"未知知识库作用域: {scope}")
    result = kb.add_doc(req.title, req.content, req.category or "")
    return ChatDocResponse(**result)
