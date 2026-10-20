"""父诉求事件拆分（API 10.1）：只拆事件，不分类别

策略：大模型事件级拆分（按独立事件拆分为多条子诉求）；
大模型不可用或无拆分建议时，整段原文作为 1 条子诉求兜底。
拆分后对每条子诉求逐条加工（清洗+归一化+脱敏）并附加工留痕。
分类由工单级 /classify 接口完成，detect 只负责拆分。
"""
import json

from loguru import logger

from app.core.ai.llm_client import LlmUnavailable, chat_json
from app.core.util.preprocess import preprocess_with_log

DETECT_SYSTEM_PROMPT = (
    "你是12345政务热线的诉求拆解引擎。请把诉求文本按「诉求主题」拆分为多个单一事件，"
    "一个主题输出一条子诉求。不要做分类（分类由后续工单分类系统完成）。"
    "拆分规则："
    "1. 主题指市民要解决的实质问题（如 占道经营、红绿灯故障、拖欠工资、油烟扰民）；"
    "2. 不同主题必须拆成不同子诉求；文本中「另外」「还有」「同时」「此外」「以及」连接的通常是不同事件，必须拆开；"
    "   即使是同一地点的不同问题（如同一路口的违章搭建和红绿灯故障），也属于不同主题，必须拆开，绝不能合并；"
    "3. 同一主题的补充描述（安全隐患、生活不便、情绪呼吁、处理诉求）必须合并进该主题，不得单独成条；"
    "4. 不要过度拆分，也不要漏拆。"
    "示例：输入「小区门口占道经营严重，另外路口红绿灯坏了三天」，"
    "应拆为两条：占道经营（小区门口占道经营严重）、红绿灯故障（路口红绿灯坏了三天）。"
    '只输出JSON：{"split_suggestions":[{"event_type":"具体事件名",'
    '"text":"拆分后的子诉求文本"}]}'
    "event_type 必须是具体事件（如 占道经营、红绿灯故障、噪音扰民），不能是泛化描述。"
    "如果整个文本只有一个事件，也输出一条 split_suggestions。"
)


def _llm_split(text: str):
    """大模型事件级拆分：只拆事件不分类别，返回 split_suggestions 列表；异常或无效时返回 None"""
    result = chat_json(DETECT_SYSTEM_PROMPT, text)
    splits = []
    for item in (result.get("split_suggestions") or []):
        if not isinstance(item, dict):
            continue
        evt_type = str(item.get("event_type") or "").strip()
        child_text = str(item.get("text") or "").strip()
        if not child_text:
            continue
        splits.append({
            "event_type": evt_type or "未命名事件",
            "text": child_text,
        })
    return splits if splits else None


def detect(text: str) -> dict:
    """只拆事件不分类别：返回 split_suggestions（分类由工单级 /classify 完成）。
    拆分基于原文，拆完后对每条子诉求逐条加工（清洗+归一化+脱敏）并附加工留痕。"""
    # 只用大模型事件级拆分
    splits = None
    method = "none"
    try:
        splits = _llm_split(text)
        if splits:
            method = "llm"
    except LlmUnavailable as e:
        logger.warning("诉求拆分大模型不可用，整段兜底：{}", e)

    # 兜底：整段原文作为 1 条子诉求（同样加工，保证 clean_content/clean_log 完整）
    if not splits:
        clean, log = preprocess_with_log(text)
        return {
            "split_suggestions": [{
                "event_type": "未命名事件",
                "text": text,
                "clean_content": clean,
                "clean_log": json.dumps(log, ensure_ascii=False) if log else None,
            }],
            "method": "none",
        }

    # 逐条加工：清洗+归一化+脱敏，并附留痕（敏感词替换/PII 脱敏）
    for sp in splits:
        clean, log = preprocess_with_log(sp.get("text", ""))
        sp["clean_content"] = clean
        sp["clean_log"] = json.dumps(log, ensure_ascii=False) if log else None

    return {"split_suggestions": splits, "method": method}
