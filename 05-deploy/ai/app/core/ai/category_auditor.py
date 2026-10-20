"""诉求类别 AI 审核（PydanticAI Agent 版）：结构化输出 consistent/reason/corrected_category。
返回 dict {"consistent": bool, "reason": "...", "corrected_category": code|None}。
"""
from app.core.ai.agents import audit_category_agent
from app.core.ai.llm_client import LlmUnavailable

CATEGORY_NAMES = {
    "URBAN": "城市管理", "TRAFFIC": "交通管理", "MARKET": "市场监管",
    "ENVIRONMENT": "环境保护", "HOUSING": "住房保障", "LABOR": "劳动保障",
    "PUBLIC": "公共服务", "HEALTH": "医疗卫生", "OTHER": "其他",
}


def audit_category(content: str, category: str) -> dict:
    """PydanticAI 类别审核"""
    try:
        return audit_category_agent(content, category)
    except LlmUnavailable:
        raise
    except Exception as e:
        raise LlmUnavailable(str(e)) from e
