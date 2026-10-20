"""四端 AI 助手（智能助手升级：PydanticAI Agent + 工具调用）。

架构：
  Java ChatController 签发用户上下文令牌（agent_context）随提问发给本服务；
  本服务为对应端构建带工具集的 PydanticAI Agent，Agent 触发工具时回调
  Java /internal/agent/* 查询权限内业务数据（数据权限由 Java 按登录用户过滤）；
  工具返回结果由 LLM 汇总成回答。知识库检索（search_kb）也从被动拼接改为主动工具，
  模型自行判断何时查业务数据、何时查办理规范。

每个端一个 Agent（工具集不同）：
  citizen 市民端：我的诉求 / 办理进度 / 诉求填写补全
  street  街道办端：本街道工单 / 详情 / 流转时间线 / 分发到本街道的预警
  staff   部门端：本部门工单 / 详情 / 流转时间线 / 工作台汇总
  admin   管理端：全量工单/全链路 / 诉求 / 日志 / 预警 / 预判 / 账号 / 待办 / 开关 / 知识库维护 / 触发扫描/预判
"""
import asyncio
from dataclasses import dataclass
from typing import Any, Optional

from loguru import logger
from pydantic_ai import Agent, ModelSettings, RunContext
from pydantic_ai.exceptions import ModelRetry
from pydantic_ai.models.openai import OpenAIChatModel
from pydantic_ai.providers.openai import OpenAIProvider

from app.config import DASHSCOPE_API_KEY, DASHSCOPE_BASE_URL, LLM_MODEL
from app.core.ai import suggester
from app.core.ai.java_client import JavaApiError, call_java

# ------------------------------------------------------------------ 常量

# 单次工具返回列表最多条目数（喂给 LLM，避免上下文过大）
_PAGE_MAX = 20
# 单字段文本截断长度（喂给 LLM 的上下文）
_TEXT_MAX = 220


def _slim(value: Any, depth: int = 0) -> Any:
    """递归精简工具返回：截断长文本、压缩长列表，避免把超大载荷喂给 LLM。"""
    if depth > 3:
        return "..."
    if isinstance(value, str):
        return value if len(value) <= _TEXT_MAX else value[:_TEXT_MAX] + "…"
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            if k in ("content", "measure", "ai_summary", "summary") and isinstance(v, str) and len(v) > _TEXT_MAX:
                out[k] = v[:_TEXT_MAX] + "…"
            else:
                out[k] = _slim(v, depth + 1)
        return out
    if isinstance(value, list):
        return [_slim(x, depth + 1) for x in value[:_PAGE_MAX]]
    return value


# ------------------------------------------------------------------ 模型与依赖

def _model():
    return OpenAIChatModel(
        LLM_MODEL,
        provider=OpenAIProvider(base_url=DASHSCOPE_BASE_URL, api_key=DASHSCOPE_API_KEY or "unused"),
    )


@dataclass
class AgentDeps:
    """一次会话的运行依赖：端作用域 + 用户上下文令牌 + 本端知识库实例"""
    scope: str
    context_token: str = ""
    kb: Any = None


# ------------------------------------------------------------------ 工具公共辅助

async def _java(ctx: RunContext[AgentDeps], method: str, path: str,
                params: Optional[dict] = None) -> Any:
    """工具内统一调用 Java 内网接口：业务失败转 ModelRetry，让模型看到错误信息后如实回答，避免整个会话崩溃回退兜底。"""
    try:
        return await call_java(method, path, params=params,
                               context_token=ctx.deps.context_token)
    except JavaApiError as e:
        raise ModelRetry(str(e)) from e


def _exact_rows(data: Any) -> list:
    """从分页返回信封 {list:[...]} 中取出列表（容错非 dict 结构）。"""
    if isinstance(data, dict):
        return data.get("list") or []
    return []


async def _resolve_order_id(ctx: RunContext[AgentDeps], scope_path: str, order: str) -> int:
    """把用户给的工单标识解析为数字ID：纯数字直接用；工单号（如 WO20260908017）则按工单号查本端列表接口反查。"""
    s = str(order or "").strip()
    if not s:
        raise ModelRetry("请提供工单号（如 WO20260908017）或工单数字ID")
    if s.isdigit():
        return int(s)
    data = await _java(ctx, "GET", f"/internal/agent/{scope_path}/orders",
                       params={"order_no": s, "page": 1, "page_size": 5})
    # 列表接口 order_no 为模糊匹配，取编号完全一致的行，避免误拿相近工单
    hit = next((r for r in _exact_rows(data) if str(r.get("order_no") or "") == s), None)
    if hit is None or hit.get("id") is None:
        raise ModelRetry(f"未找到工单号 {s} 对应的工单，请确认工单号是否正确")
    return int(hit["id"])


async def _resolve_parent_appeal_id(ctx: RunContext[AgentDeps], parent: str) -> int:
    """把用户给的父诉求标识解析为数字ID：纯数字直接用；父诉求编号（AP开头）则按编号查父诉求列表反查。"""
    s = str(parent or "").strip()
    if not s:
        raise ModelRetry("请提供父诉求编号（AP开头）或父诉求数字ID")
    if s.isdigit():
        return int(s)
    data = await _java(ctx, "GET", "/internal/agent/admin/appeals",
                       params={"appeal_no": s, "scope": "parent", "page": 1, "page_size": 5})
    hit = next((r for r in _exact_rows(data) if str(r.get("appeal_no") or "") == s), None)
    if hit is None or hit.get("id") is None:
        raise ModelRetry(f"未找到父诉求编号 {s}（仅支持 AP 开头的父诉求编号）")
    return int(hit["id"])


async def _resolve_citizen_appeal_no(ctx: RunContext[AgentDeps], appeal: str) -> str:
    """把市民给的诉求标识解析为父诉求编号（AP开头）：编号直接用；纯数字（诉求ID）则查本人诉求列表反查编号。"""
    s = str(appeal or "").strip()
    if not s:
        raise ModelRetry("请提供父诉求编号（AP开头，如 AP20260908000001）")
    if not s.isdigit():
        return s
    data = await _java(ctx, "GET", "/internal/agent/citizen/appeals",
                       params={"page": 1, "page_size": 20})
    hit = next((r for r in _exact_rows(data) if str(r.get("id") or "") == s), None)
    if hit is None or not hit.get("appeal_no"):
        raise ModelRetry(f"未在您的诉求中找到编号为 {s} 的记录，请提供 AP 开头的父诉求编号")
    return str(hit["appeal_no"])


# ------------------------------------------------------------------ 人设与工具规则

_PERSONA = {
    "citizen": (
        "你是「12345政务服务热线」的智能助手小政。"
        "能回答市民的诉求办理进度、相关政策与办事流程问题；涉及本平台个人的诉求数据时调用工具查询，"
        "查不到时如实说明，不要编造；办理规范/流程类问题优先检索知识库。"
        "用简洁、友好、口语化的中文回答。"
    ),
    "street": (
        "你是「12345政务热线」街道办端智能工作助手。面向街道办工作人员："
        "涉及本辖区工单、流转时间线、分发到本街道的预警等数据时调用工具查询（仅限本街道权限内数据），"
        "办理规范/属地责任等规则优先检索知识库；数据查不到或权限外时如实说明，不得编造。"
        "用专业、简洁的中文回答。"
    ),
    "staff": (
        "你是「12345政务热线」部门处置端智能工作助手。面向部门工作人员："
        "涉及本部门工单、详情、流转时间线、工作台汇总等数据时调用工具查询（仅限本部门权限内数据），"
        "办理时限/退回/延期等规则优先检索知识库；查不到或权限外如实说明，不得编造。"
        "用专业、简洁的中文回答。"
    ),
    "admin": (
        "你是「12345政务热线」平台管理端智能工作助手。面向系统管理员："
        "可查询全平台工单、诉求、审计日志、预警、预判、账号、待办与系统开关等数据，可维护知识库，"
        "可触发预警扫描与预判；回答任何数据类问题时必须调用工具查询真实数据，查不到如实说明，不得编造；"
        "管理/配置规范类问题优先检索知识库。用专业、简洁的中文回答。"
    ),
}

_TOOL_RULES = (
    "重要操作规则：\n"
    "1. 用户问的是业务数据（工单、诉求、日志、预警、预判、账号、统计、进度等）时，必须先调用对应工具查询，"
    "   禁止凭感觉编造工单号、数量、状态、时间等任何数据；\n"
    "2. 工具返回空或报错时如实告知用户，并给出可能的下一步建议；\n"
    "3. 涉及办理流程/政策/规范的知识问答，先用 search_kb 检索本端知识库；\n"
    "4. 你只能看到当前登录账号权限范围内的数据，不要向用户索要身份信息去查他人数据；\n"
    "5. 数字/时间类信息以工具返回为准；列表类结果先汇总要点再展示，避免罗列过长；\n"
    "6. 你拥有实时数据查询工具，严禁声称「无法调取/访问实时数据」；用户给出工单号（WO开头）或诉求编号"
    "（AP/AS开头）时直接传入对应工具的编号参数（工具同时支持编号与数字ID），不得因拿到的是编号而放弃查询。\n"
)


def _prompt(scope: str) -> str:
    return _PERSONA.get(scope, _PERSONA["citizen"]) + "\n\n" + _TOOL_RULES


# ------------------------------------------------------------------ 工具：四端通用

def _register_common(agent: Agent) -> None:
    @agent.tool
    async def search_kb(ctx: RunContext[AgentDeps], question: str) -> str:
        """检索当前登录端的内置/已维护知识库（办理流程、政策规范、平台操作说明等）。回答规则类问题前应先检索。"""
        if ctx.deps.kb is None:
            return "知识库不可用"
        try:
            docs = await asyncio.to_thread(ctx.deps.kb.search, question)
        except Exception as e:  # noqa: BLE001
            logger.warning("search_kb 检索失败: {}", e)
            return "知识库检索失败"
        if not docs:
            return "知识库未命中相关内容，如实告知用户可咨询人工或页面操作"
        return "\n\n".join(f"【{d['title']}】{d['content'][:_TEXT_MAX]}" for d in docs[:4])


# ------------------------------------------------------------------ 工具：市民端

def _register_citizen(agent: Agent) -> None:
    @agent.tool
    async def query_my_appeals(ctx: RunContext[AgentDeps], appeal_no: Optional[str] = None,
                               page: int = 1, page_size: int = 10) -> dict:
        """查询当前市民账号自己提交的诉求列表（可填诉求编号精确查，否则分页）。"""
        data = await _java(ctx, "GET", "/internal/agent/citizen/appeals",
                           params={"appeal_no": appeal_no or "", "page": page, "page_size": page_size})
        return _slim(data)

    @agent.tool
    async def get_appeal_progress(ctx: RunContext[AgentDeps], appeal: str) -> dict:
        """查询某诉求的办理进度与流转时间线（AI拆分→部门签收→办理→办结等节点）。appeal 支持父诉求编号（AP开头）或诉求数字ID。"""
        appeal_no = await _resolve_citizen_appeal_no(ctx, appeal)
        return _slim(await _java(ctx, "GET", f"/internal/agent/citizen/appeals/{appeal_no}/progress"))

    @agent.tool_plain
    async def draft_appeal_suggest(partial_content: str) -> dict:
        """根据市民已输入的部分诉求内容，智能补全标题/描述并提示缺失要素（仅生成草稿建议回显给用户填表，不直接提交）。"""
        try:
            return await asyncio.to_thread(suggester.suggest, partial_content)
        except Exception as e:  # noqa: BLE001
            logger.warning("draft_appeal_suggest 失败: {}", e)
            return {"suggested_title": "", "completion": "", "missing_elements": []}


# ------------------------------------------------------------------ 工具：街道办端

def _register_street(agent: Agent) -> None:
    @agent.tool
    async def query_orders(ctx: RunContext[AgentDeps], order_no: Optional[str] = None,
                           keyword: Optional[str] = None, status: Optional[str] = None,
                           start_time: Optional[str] = None, end_time: Optional[str] = None,
                           page: int = 1, page_size: int = 10) -> dict:
        """查询本街道辖区的工单列表（工单号/关键词/状态/时间范围过滤，分页）。"""
        data = await _java(ctx, "GET", "/internal/agent/street/orders",
                           params={"order_no": order_no or "", "keyword": keyword or "", "status": status or "",
                                   "start_time": start_time or "", "end_time": end_time or "",
                                   "page": page, "page_size": page_size})
        return _slim(data)

    @agent.tool
    async def get_order_detail(ctx: RunContext[AgentDeps], order: str) -> dict:
        """查询本街道辖区某工单的详情（含流转时间线）。order 支持工单号（如 WO20260908017）或工单数字ID。"""
        order_id = await _resolve_order_id(ctx, "street", order)
        return _slim(await _java(ctx, "GET", f"/internal/agent/street/orders/{order_id}"))

    @agent.tool
    async def get_order_timeline(ctx: RunContext[AgentDeps], order: str) -> dict:
        """查询本街道辖区某工单的流转时间线。order 支持工单号（如 WO20260908017）或工单数字ID。"""
        order_id = await _resolve_order_id(ctx, "street", order)
        return _slim(await _java(ctx, "GET", f"/internal/agent/street/orders/{order_id}/timeline"))

    @agent.tool
    async def query_dispatched_warnings(ctx: RunContext[AgentDeps], warning_level: Optional[str] = None,
                                        sub_category: Optional[str] = None,
                                        start_time: Optional[str] = None, end_time: Optional[str] = None,
                                        page: int = 1, page_size: int = 10) -> dict:
        """查询分发到本街道的预警记录（可筛选预警等级、二级分类、时间范围）。"""
        data = await _java(ctx, "GET", "/internal/agent/street/warnings",
                           params={"warning_level": warning_level or "", "sub_category": sub_category or "",
                                   "start_time": start_time or "", "end_time": end_time or "",
                                   "page": page, "page_size": page_size})
        return _slim(data)


# ------------------------------------------------------------------ 工具：部门端

def _register_staff(agent: Agent) -> None:
    @agent.tool
    async def query_orders(ctx: RunContext[AgentDeps], order_no: Optional[str] = None,
                           keyword: Optional[str] = None, status: Optional[str] = None,
                           street_office: Optional[str] = None,
                           start_time: Optional[str] = None, end_time: Optional[str] = None,
                           page: int = 1, page_size: int = 10) -> dict:
        """查询本部门权限内的工单列表（可凭证号/关键词/状态/街道办/时间过滤，分页）。"""
        data = await _java(ctx, "GET", "/internal/agent/staff/orders",
                           params={"order_no": order_no or "", "keyword": keyword or "", "status": status or "",
                                   "street_office": street_office or "",
                                   "start_time": start_time or "", "end_time": end_time or "",
                                   "page": page, "page_size": page_size})
        return _slim(data)

    @agent.tool
    async def get_order_detail(ctx: RunContext[AgentDeps], order: str) -> dict:
        """查询本部门权限内某工单的详情。order 支持工单号（如 WO20260908017）或工单数字ID。"""
        order_id = await _resolve_order_id(ctx, "staff", order)
        return _slim(await _java(ctx, "GET", f"/internal/agent/staff/orders/{order_id}"))

    @agent.tool
    async def get_order_timeline(ctx: RunContext[AgentDeps], order: str) -> dict:
        """查询本部门权限内某工单的流转时间线。order 支持工单号（如 WO20260908017）或工单数字ID。"""
        order_id = await _resolve_order_id(ctx, "staff", order)
        return _slim(await _java(ctx, "GET", f"/internal/agent/staff/orders/{order_id}/timeline"))

    @agent.tool
    async def get_workbench_summary(ctx: RunContext[AgentDeps]) -> dict:
        """查询本部门工作台汇总（待签收/办理中/超时等统计）。"""
        return _slim(await _java(ctx, "GET", "/internal/agent/staff/workbench"))


# ------------------------------------------------------------------ 工具：管理端

def _register_admin(agent: Agent) -> None:
    @agent.tool
    async def query_orders(ctx: RunContext[AgentDeps], order_no: Optional[str] = None,
                           category: Optional[str] = None, level: Optional[str] = None,
                           region: Optional[str] = None, status: Optional[str] = None,
                           keyword: Optional[str] = None, archived: Optional[int] = None,
                           page: int = 1, page_size: int = 10) -> dict:
        """查询全平台工单列表（多条件过滤，分页；archived 1=已归档/0=未归档留空=全部）。"""
        data = await _java(ctx, "GET", "/internal/agent/admin/orders",
                           params={"order_no": order_no or "", "category": category or "", "level": level or "",
                                   "region": region or "", "status": status or "", "keyword": keyword or "",
                                   "archived": archived or "", "page": page, "page_size": page_size})
        return _slim(data)

    @agent.tool
    async def get_order_full_chain(ctx: RunContext[AgentDeps], order: str) -> dict:
        """查询某工单的全链路信息（督办/流转/处理全要素）。order 支持工单号（如 WO20260908017）或工单数字ID。"""
        order_id = await _resolve_order_id(ctx, "admin", order)
        return _slim(await _java(ctx, "GET", f"/internal/agent/admin/orders/{order_id}/full-chain"))

    @agent.tool
    async def query_appeals(ctx: RunContext[AgentDeps], appeal_no: Optional[str] = None,
                            status: Optional[str] = None, keyword: Optional[str] = None,
                            scope: str = "parent", page: int = 1, page_size: int = 10) -> dict:
        """查询诉求列表（scope=parent 父诉求 / child 子诉求，可凭证号/状态/关键词过滤）。"""
        data = await _java(ctx, "GET", "/internal/agent/admin/appeals",
                           params={"appeal_no": appeal_no or "", "status": status or "", "keyword": keyword or "",
                                   "scope": scope, "page": page, "page_size": page_size})
        return _slim(data)

    @agent.tool
    async def get_appeal_children(ctx: RunContext[AgentDeps], parent: str) -> dict:
        """查询某父诉求下的全部子诉求明细（含关联工单信息）。parent 支持父诉求编号（AP开头）或数字ID。"""
        parent_id = await _resolve_parent_appeal_id(ctx, parent)
        return _slim(await _java(ctx, "GET", f"/internal/agent/admin/appeals/{parent_id}/children"))

    @agent.tool
    async def query_logs(ctx: RunContext[AgentDeps], view: str = "system",
                         log_type: Optional[str] = None, operator: Optional[str] = None,
                         module: Optional[str] = None, dept: Optional[str] = None,
                         county: Optional[str] = None, appeal_keyword: Optional[str] = None,
                         start_time: Optional[str] = None, end_time: Optional[str] = None,
                         page: int = 1, page_size: int = 10) -> dict:
        """查询系统审计/操作日志。view=system 平台系统日志、dept 部门审计、street 街道办审计；可按类型/操作人/模块/部门/区县/关键词/时间过滤。"""
        data = await _java(ctx, "GET", "/internal/agent/admin/logs",
                           params={"view": view, "log_type": log_type or "", "operator": operator or "",
                                   "module": module or "", "dept": dept or "", "county": county or "",
                                   "appeal_keyword": appeal_keyword or "",
                                   "start_time": start_time or "", "end_time": end_time or "",
                                   "page": page, "page_size": page_size})
        return _slim(data)

    @agent.tool
    async def query_warnings(ctx: RunContext[AgentDeps], warning_level: Optional[str] = None,
                             category: Optional[str] = None, region: Optional[str] = None,
                             status: Optional[str] = None,
                             start_time: Optional[str] = None, end_time: Optional[str] = None,
                             page: int = 1, page_size: int = 10) -> dict:
        """查询预警记录（按等级/分类/辖区/审核状态/时间过滤，分页）。"""
        data = await _java(ctx, "GET", "/internal/agent/admin/warnings",
                           params={"warning_level": warning_level or "", "category": category or "",
                                   "region": region or "", "status": status or "",
                                   "start_time": start_time or "", "end_time": end_time or "",
                                   "page": page, "page_size": page_size})
        return _slim(data)

    @agent.tool
    async def query_warning_rules(ctx: RunContext[AgentDeps]) -> dict:
        """查询全部预警规则（名称/时间维度/分类/阈值/等级/状态）。"""
        return _slim(await _java(ctx, "GET", "/internal/agent/admin/warning-rules"))

    @agent.tool
    async def query_prediction(ctx: RunContext[AgentDeps], part: str = "hotspots",
                               category: Optional[str] = None, sub_category: Optional[str] = None,
                               region: Optional[str] = None, period: Optional[str] = None) -> dict:
        """查询预判结果。part=hotspots 热点（默认，可按分类/辖区/周期过滤）、trend 未来趋势、accuracy 预判准确率。"""
        return _slim(await _java(ctx, "GET", "/internal/agent/admin/prediction",
                                 params={"part": part, "category": category or "",
                                         "sub_category": sub_category or "",
                                         "region": region or "", "period": period or ""}))

    @agent.tool
    async def query_users(ctx: RunContext[AgentDeps], name: Optional[str] = None,
                          job_no: Optional[str] = None, user_type: Optional[str] = None,
                          status: Optional[str] = None, page: int = 1, page_size: int = 10) -> dict:
        """查询内部账号列表（按姓名/工号/归属类型/状态过滤，分页）。"""
        data = await _java(ctx, "GET", "/internal/agent/admin/users",
                           params={"name": name or "", "job_no": job_no or "", "user_type": user_type or "",
                                   "status": status or "", "page": page, "page_size": page_size})
        return _slim(data)

    @agent.tool
    async def query_pending_accounts(ctx: RunContext[AgentDeps], status: Optional[str] = None,
                                     name: Optional[str] = None, job_no: Optional[str] = None,
                                     page: int = 1, page_size: int = 10) -> dict:
        """查询待审核账号列表（可按审核状态/姓名/工号过滤）。"""
        data = await _java(ctx, "GET", "/internal/agent/admin/pending-accounts",
                           params={"status": status or "", "name": name or "", "job_no": job_no or "",
                                   "page": page, "page_size": page_size})
        return _slim(data)

    @agent.tool
    async def get_todo_summary(ctx: RunContext[AgentDeps]) -> dict:
        """查询全局待办汇总（待审核账号/待分派/待终审等数量）。"""
        return _slim(await _java(ctx, "GET", "/internal/agent/admin/todo-summary"))

    @agent.tool
    async def get_system_switches(ctx: RunContext[AgentDeps]) -> dict:
        """查询系统自动处理开关状态（诉求自动拆分/自动建单/自动派单）。"""
        return _slim(await _java(ctx, "GET", "/internal/agent/admin/switches"))

    @agent.tool
    async def upload_kb_doc(ctx: RunContext[AgentDeps], title: str, content: str,
                            category: Optional[str] = None) -> dict:
        """向管理端知识库写入一篇知识文档（维护 FAQ/内参）。title 必填，content 为正文。"""
        if ctx.deps.kb is None:
            raise ModelRetry("知识库不可用，暂时无法维护文档")
        t, c = (title or "").strip(), (content or "").strip()
        if not t or not c:
            raise ModelRetry("文档入库需要非空的 title 与 content")
        try:
            return await asyncio.to_thread(ctx.deps.kb.add_doc, t, c, category or "")
        except Exception as e:  # noqa: BLE001
            logger.warning("upload_kb_doc 失败: {}", e)
            raise ModelRetry(f"知识库写入失败：{e}") from e

    @agent.tool
    async def trigger_warning_scan(ctx: RunContext[AgentDeps]) -> dict:
        """手动触发一次预警扫描（遍历启用规则，超阈值即生成待审核预警）。"""
        return _slim(await _java(ctx, "POST", "/internal/agent/admin/warning-scan"))

    @agent.tool
    async def trigger_prediction(ctx: RunContext[AgentDeps]) -> dict:
        """手动触发一次诉求预判（刷新统计桥并产出新的预判热点与趋势）。"""
        return _slim(await _java(ctx, "POST", "/internal/agent/admin/predict"))


_REGISTER = {
    "citizen": _register_citizen,
    "street": _register_street,
    "staff": _register_staff,
    "admin": _register_admin,
}


# ------------------------------------------------------------------ Agent 构建

_agents: dict[str, Agent] = {}


def get_agent(scope: str) -> Agent:
    """懒构建对应端的 AI 助手 Agent（工具集按端注册，进程内缓存）。"""
    if scope not in _REGISTER:
        raise ValueError(f"未知 AI 助手作用域: {scope}")
    cached = _agents.get(scope)
    if cached is not None:
        return cached
    agent = Agent(
        _model(),
        deps_type=AgentDeps,
        system_prompt=_prompt(scope),
        retries=2,
        model_settings=ModelSettings(timeout=90, tool_choice="auto"),
    )
    _register_common(agent)
    _REGISTER[scope](agent)
    _agents[scope] = agent
    return agent
