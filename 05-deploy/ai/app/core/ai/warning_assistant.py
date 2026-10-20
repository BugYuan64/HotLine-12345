"""预警 AI 总结与应对措施推荐（API 10.9/10.10）

- 预警总结：基于预警关联工单文本集生成结构化总结（含置信度，Java 侧入库时追加"AI生成·置信度X%"标识）
- 应对措施：基于预警场景推荐措施列表及理由
大模型不可用时抛 LlmUnavailable，由路由层返回 503，Java 侧按「AI 降级特殊响应」处理。
"""
from loguru import logger

from app.config import SUGGEST_MODEL
from app.core.ai.llm_client import LlmUnavailable, chat_json

WARNING_SUMMARY_PROMPT = (
    "你是12345政务热线工单分析助手。根据给定的预警关联工单文本列表，"
    "生成一段不超过200字的预警总结，说明：诉求集中的具体问题、涉及区域/小区、主要影响范围。"
    '只输出JSON，格式：{"summary": "总结内容", "confidence": 0.0到1.0之间的小数}'
)

WARNING_MEASURE_PROMPT = (
    "你是12345政务热线处置决策助手。输入会包含【预警信息】【窗口内命中工单明细】【近期同类概览】【预警总结】四部分，"
    "可能还包含第五部分【参考优秀处置案例】（历史同类型工单的成熟处置做法，来自处置案例库）。"
    "请结合其中的具体辖区、一二级分类、聚焦事件/小区和关注度，给出有针对性、可落地的处置措施，"
    "避免「加强巡查」「督促整改」这类空泛表述，应尽量引用具体对象与数据；"
    "当存在【参考优秀处置案例】时，优先借鉴其中的成熟做法，但需结合本次预警的具体对象加以调整。"
    "推荐2~4条措施，每条附一条推荐理由。"
    '只输出JSON，格式：{"measures": [{"measure": "措施内容", "reason": "推荐理由"}]}'
)


def summarize_warning(warning_id: int, order_texts: list) -> dict:
    """预警总结生成（API 10.9）：返回 {"summary": str, "confidence": float}"""
    if not order_texts:
        return {"summary": "", "confidence": 0.0}
    user = "预警ID：" + str(warning_id) + "\n关联工单文本：\n" + "\n---\n".join(order_texts[:100])
    try:
        # 后台生成任务：用轻量快模型（qwen-turbo，1-3s）保证 Java 15s 内返回不降级；
        # 主模型 deepseek 非流式常超时（曾致 /ai/v1/warning/measure Read timed out）
        data = chat_json(WARNING_SUMMARY_PROMPT, user, model=SUGGEST_MODEL)
        # str(None) 会产出字面量 "None"，统一 or "" 兜底
        summary = str(data.get("summary") or "").strip()
        try:
            confidence = float(data.get("confidence") or 0.5)
        except (TypeError, ValueError):
            confidence = 0.5
        confidence = max(0.0, min(1.0, confidence))
        return {"summary": summary, "confidence": round(confidence, 4)}
    except LlmUnavailable:
        raise
    except Exception as e:
        # 输出解析异常：返回空总结（Java 侧按无 AI 总结处理，不阻断预警入库）
        logger.warning("预警总结解析失败 warning_id={}：{}", warning_id, e)
        return {"summary": "", "confidence": 0.0}


def recommend_measures(scene: str) -> dict:
    """应对措施推荐（API 10.10）：返回 {"measures": [{"measure","reason"}]}"""
    try:
        # 同总结：快模型保证在 Java 15s 读超时前返回（曾 Read timed out 降级）
        data = chat_json(WARNING_MEASURE_PROMPT, scene, model=SUGGEST_MODEL)
        items = []
        for m in (data.get("measures") or [])[:4]:
            if not isinstance(m, dict):
                continue
            measure = str(m.get("measure") or "").strip()
            reason = str(m.get("reason") or "").strip()
            if measure:
                items.append({"measure": measure, "reason": reason})
        return {"measures": items}
    except LlmUnavailable:
        raise
    except Exception as e:
        logger.warning("应对措施推荐解析失败：{}", e)
        return {"measures": []}
