"""智能诉求引导与补全（需求 6.3(13) / API 10.4）：推荐标题、补全描述、提示缺失要素"""
from app.config import SUGGEST_MODEL
from app.core.ai.llm_client import LlmUnavailable, chat_json

SUGGEST_SYSTEM_PROMPT = (
    "你是12345政务热线的智能诉求填写助手。基于市民已输入的部分诉求内容："
    "1) 推荐一个简洁的诉求标题；2) 补全描述建议（规范化的完整表述）；"
    "3) 提示缺失的关键要素（如具体点位、发生时间、涉及对象等）。"
    '只输出JSON：{"suggested_title":"...","completion":"...","missing_elements":["...","..."]}'
)


def suggest(partial_content: str) -> dict:
    """大模型引导补全；不可用时抛出 LlmUnavailable（由 Java 侧按降级特殊响应处理）"""
    if not partial_content or len(partial_content.strip()) < 2:
        return {"suggested_title": "", "completion": "", "missing_elements": []}
    # 打字停顿实时触发的高频交互链路：用轻量快模型（qwen-turbo，关 thinking 后 1-3s），
    # 主模型 qwen3.8-max 即使关思考也常 10s+，超过 Java 15s 读超时导致整链路降级
    result = chat_json(SUGGEST_SYSTEM_PROMPT, partial_content, model=SUGGEST_MODEL)
    # 防御：LLM 偶发输出字符串而非数组，统一规整为 List[str]，避免响应模型校验失败
    missing = result.get("missing_elements") or []
    if not isinstance(missing, list):
        missing = [str(missing)] if str(missing).strip() else []
    missing = [str(m).strip() for m in missing if str(m).strip()]
    return {
        "suggested_title": str(result.get("suggested_title") or ""),
        "completion": str(result.get("completion") or ""),
        "missing_elements": missing,
    }
