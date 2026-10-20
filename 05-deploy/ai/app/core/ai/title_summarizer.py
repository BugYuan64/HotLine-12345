"""诉求标题 AI 总结（PydanticAI Agent 版）：结构化输出标题，端点契约不变。
返回 dict {"title": "..."}；不可用时抛 LlmUnavailable（Java 侧按降级处理）。
"""
from app.core.ai.agents import summarize_title_agent
from app.core.ai.llm_client import LlmUnavailable


def summarize_title(content: str) -> dict:
    """PydanticAI 标题生成"""
    try:
        return summarize_title_agent(content)
    except LlmUnavailable:
        raise
    except Exception as e:
        # Agent 校验/调用异常统一按降级信号抛出
        raise LlmUnavailable(str(e)) from e
