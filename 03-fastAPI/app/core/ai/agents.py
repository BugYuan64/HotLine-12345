"""PydanticAI Agent 层：子诉求 AI 链路（标题生成 / 类别审核 / 类别分类）结构化实现。
- 端点契约与旧实现一致（返回 dict），Java 侧无感知
- 输出用 Pydantic 模型校验（9 大类 + OTHER 为合法候选），校验失败自动重试
- Agent 协程统一在进程内专用事件循环上执行（见 _get_agent_loop），
  修复多 worker 线程 run_sync 各建事件循环导致的 httpx 连接池跨循环绑定错误
"""
import asyncio
import threading
from typing import Any, Literal, Optional

from loguru import logger
from pydantic import BaseModel, Field
from pydantic_ai import Agent, ModelSettings
from pydantic_ai.models.openai import OpenAIChatModel
from pydantic_ai.providers.openai import OpenAIProvider

from app.config import DASHSCOPE_API_KEY, DASHSCOPE_BASE_URL, LLM_MODEL
from app.core.ai.llm_client import LlmUnavailable

# ------------------------------------------------------------------ 模型与候选

CATEGORY_LITERAL = Literal[
    "URBAN", "TRAFFIC", "MARKET", "ENVIRONMENT",
    "HOUSING", "LABOR", "PUBLIC", "HEALTH", "OTHER",
]

CATEGORY_NAMES = {
    "URBAN": "城市管理", "TRAFFIC": "交通管理", "MARKET": "市场监管",
    "ENVIRONMENT": "环境保护", "HOUSING": "住房保障", "LABOR": "劳动保障",
    "PUBLIC": "公共服务", "HEALTH": "医疗卫生", "OTHER": "其他",
}


def _model():
    return OpenAIChatModel(
        LLM_MODEL,
        provider=OpenAIProvider(
            base_url=DASHSCOPE_BASE_URL,
            api_key=DASHSCOPE_API_KEY or "unused",
        ),
    )


def _agent_settings() -> ModelSettings:
    """Agent 统一请求设置：qwen3 系列禁用思考模式。
    根因：结构化输出 Agent 必带 tool_choice=required，而 DashScope 思考模式禁止
    tool_choice 设为 required/object（400 InvalidParameter），会导致标题/审核/分类
    全部 Agent 降级；qwen3 系列通过 extra_body 传 enable_thinking=False 关闭，
    其余模型（如 deepseek）不传该参数避免校验报错（与 chat_json 同一处理方式）。"""
    settings: ModelSettings = {"timeout": 12}
    if "qwen3" in str(LLM_MODEL):
        settings["extra_body"] = {"enable_thinking": False}
    return settings


# ------------------------------------------------------------------ Agent 专用事件循环

_agent_loop: Optional[asyncio.AbstractEventLoop] = None
_agent_loop_lock = threading.Lock()


def _get_agent_loop() -> asyncio.AbstractEventLoop:
    """进程内唯一的 Agent 专用事件循环（后台守护线程 run_forever 驱动）。

    根因修复：FastAPI 同步端点运行在 anyio 线程池的多个 worker 线程上，
    run_sync 会在每个线程各建一个事件循环；而 Agent/httpx AsyncClient 是模块级
    全局缓存，连接池中的 asyncio.Event 绑定首次使用的循环，跨线程复用即抛
    'Event object is bound to a different event loop'（表现为分类大面积 500/超时）。
    所有 Agent 协程统一提交到本循环执行即可根治。
    """
    global _agent_loop
    with _agent_loop_lock:
        if _agent_loop is None or _agent_loop.is_closed():
            _agent_loop = asyncio.new_event_loop()
            threading.Thread(target=_agent_loop.run_forever, daemon=True,
                             name="pydantic-ai-loop").start()
    return _agent_loop


def _run_agent(agent: Agent, prompt: str) -> Any:
    """在专用事件循环上同步执行 agent.run 并等待结果（线程安全，多请求可并发提交）。

    任何执行异常（连接错误/输出校验超限等）统一转 LlmUnavailable，
    走各调用方既有的降级契约（503/降级响应），不再以 500 击穿链路。
    """
    future = asyncio.run_coroutine_threadsafe(agent.run(prompt), _get_agent_loop())
    try:
        return future.result()
    except LlmUnavailable:
        raise
    except Exception as e:  # noqa: BLE001
        logger.warning("PydanticAI Agent 执行失败（按 LLM 不可用降级）：{}", e)
        raise LlmUnavailable(f"Agent 执行失败: {e}") from e


# ------------------------------------------------------------------ 标题 Agent

class TitleResult(BaseModel):
    """诉求标题：一句话概括核心问题，突出对象与事项，不超过 25 字"""
    title: str = Field(description="简洁标题，不超过25字")


_title_agent: Optional[Agent] = None


def _get_title_agent() -> Agent:
    global _title_agent
    if _title_agent is None:
        _title_agent = Agent(
            _model(),
            system_prompt=(
                "你是12345政务热线的诉求标题生成助手。根据市民诉求内容生成简洁标题："
                "1) 一句话概括核心问题，突出对象与事项（如：某小区电梯频繁故障）；"
                "2) 标题不超过25个字；3) 只输出标题本身。"
            ),
            output_type=TitleResult,
            retries=2,
            model_settings=_agent_settings(),
        )
    return _title_agent


def summarize_title_agent(content: str) -> dict:
    """PydanticAI 标题生成：结构化输出 title"""
    if not content or len(content.strip()) < 2:
        return {"title": ""}
    result = _run_agent(_get_title_agent(), content)
    title = (result.output.title or "").strip().strip('"“”')
    if len(title) > 25:
        title = title[:25]
    return {"title": title}


# ------------------------------------------------------------------ 审核 Agent

class AuditResult(BaseModel):
    """内容与给定类别一致性判断"""
    consistent: bool = Field(description="内容是否属于给定类别")
    reason: str = Field(default="", description="一句话判断依据")
    corrected_category: Optional[CATEGORY_LITERAL] = Field(
        default=None, description="不一致时给出最合适的类别（从候选类别中选择）")


_audit_agent: Optional[Agent] = None


def _get_audit_agent() -> Agent:
    global _audit_agent
    if _audit_agent is None:
        names = "、".join(f"{k}={v}" for k, v in CATEGORY_NAMES.items())
        _audit_agent = Agent(
            _model(),
            system_prompt=(
                "你是12345政务热线的诉求分类审核员。判断诉求内容与给定类别是否一致："
                "1) 确实属于该类别→consistent=true；"
                "2) 明显不符（如内容是环境污染却标为教育类）→consistent=false，并在 "
                "corrected_category 中给出最合适的类别；"
                "3) 给出一句话依据 reason。"
                f"可选类别：{names}。"
            ),
            output_type=AuditResult,
            retries=2,
            model_settings=_agent_settings(),
        )
    return _audit_agent


def audit_category_agent(content: str, category: str) -> dict:
    """PydanticAI 类别审核：结构化输出 consistent/reason/corrected_category"""
    cat_name = CATEGORY_NAMES.get(category or "", category or "其他")
    user = (
        f"诉求内容：\n{content}\n\n"
        f"给定类别：{cat_name}（code={category or 'OTHER'}）\n"
        "请判断该诉求内容是否属于此类别。"
    )
    result = _run_agent(_get_audit_agent(), user)
    return {
        "consistent": result.output.consistent,
        "reason": result.output.reason or "",
        "corrected_category": result.output.corrected_category,
    }


# ------------------------------------------------------------------ 分类 Agent

class ClassifyResult(BaseModel):
    """诉求一级分类：从 9 个候选类别中选择（OTHER=其他）"""
    primary_category: CATEGORY_LITERAL = Field(description="一级分类代码")
    sub_category: str = Field(
        min_length=1,
        description="二级细分标签：必须从该一级类下的二级标签清单中选择一个；OTHER 时填'其他'")
    confidence: float = Field(default=0.5, ge=0, le=1, description="置信度 0-1")
    reason: str = Field(default="", description="简短理由")


_classify_agent: Optional[Agent] = None


def _get_classify_agent() -> Agent:
    global _classify_agent
    if _classify_agent is None:
        names = "、".join(f"{k}={v}" for k, v in CATEGORY_NAMES.items())
        _classify_agent = Agent(
            _model(),
            system_prompt=(
                "你是12345政务热线的工单智能分类引擎。将市民诉求文本分类到最合适的一级分类。"
                "若内容不属于任何明确大类（如单纯咨询/表扬/建议），输出 OTHER（其他）。"
                f"可选类别：{names}。"
            ),
            output_type=ClassifyResult,
            retries=2,
            model_settings=_agent_settings(),
        )
    return _classify_agent


def classify_agent(text: str, labels: Optional[dict] = None) -> dict:
    """PydanticAI 分类：结构化输出 primary_category/sub_category/confidence/reason。
    labels：一级类→二级标签清单（Redis cls:labels），注入提示词强制模型从清单选二级。"""
    user = text
    if labels:
        entries = "\n".join(
            f"{code}({CATEGORY_NAMES.get(code, code)}): {'、'.join(v if isinstance(v, list) else str(v).split(','))}"
            for code, v in labels.items())
        user = text + "\n\n各一级类可选的二级标签清单（sub_category 必须从所选一级类对应的清单中选择；该清单中均不适合时填'其他'）：\n" + entries
    result = _run_agent(_get_classify_agent(), user)
    return {
        "primary_category": result.output.primary_category,
        "sub_category": result.output.sub_category,
        "confidence": result.output.confidence,
        "reason": result.output.reason or "",
    }


# ------------------------------------------------------------------ 二级分类 Agent（一级已定，仅选二级标签）

class SubClassifyResult(BaseModel):
    """诉求二级分类：一级分类已由审核/一级分类阶段确定，仅在指定一级类下选二级标签"""
    sub_category: str = Field(
        min_length=1,
        description="二级细分标签：必须从指定一级类下的二级标签清单中选择一个；清单为空时填'其他'")
    confidence: float = Field(default=0.5, ge=0, le=1, description="置信度 0-1")
    reason: str = Field(default="", description="简短理由")


_sub_classify_agent: Optional[Agent] = None


def _get_sub_classify_agent() -> Agent:
    global _sub_classify_agent
    if _sub_classify_agent is None:
        _sub_classify_agent = Agent(
            _model(),
            system_prompt=(
                "你是12345政务热线的工单智能分类引擎。一级分类已经确定，"
                "你的任务是为该诉求选择最合适的二级细分标签，只能从给定清单中选择；"
                "若清单中没有任何标签适合该诉求，sub_category 填'其他'。"
            ),
            output_type=SubClassifyResult,
            retries=2,
            model_settings=_agent_settings(),
        )
    return _sub_classify_agent


def sub_classify_agent(text: str, primary: str, labels: Optional[dict] = None) -> dict:
    """PydanticAI 二级分类：一级已定（primary），从该一级类的二级标签清单中选 sub_category。
    labels：一级类→二级标签清单（Redis cls:labels）。"""
    subs = []
    if labels:
        v = labels.get(primary)
        subs = v if isinstance(v, list) else (str(v).split(",") if v else [])
    user = text + f"\n\n一级分类已确定为：{primary}({CATEGORY_NAMES.get(primary, primary)})"
    if subs:
        user += "\n该一级类可选的二级标签清单（sub_category 必须从中选择一个；均不适合时填'其他'）：\n" + "、".join(subs)
    else:
        user += "\n该一级类暂无二级标签清单，sub_category 填'其他'。"
    result = _run_agent(_get_sub_classify_agent(), user)
    return {
        "sub_category": result.output.sub_category,
        "confidence": result.output.confidence,
        "reason": result.output.reason or "",
    }


def pydantic_ai_available() -> bool:
    """探测 Agent 层可用性（避免旧代码误用）"""
    return True
