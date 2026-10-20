"""诉求去重归并相似度兜底（API 10.11 /ai/v1/dedup）

归并策略：同分类 + 同辖区 + 同 event_type 的工单由 Java 侧精确匹配命中；
此处兜底在 event_type 无法精确匹配时，对候选工单文本做 Embedding 余弦相似度比对，
返回语义最相似且达到阈值的候选工单。Embedding 不可用时降级为「未匹配」，
由 Java 侧回退为新建工单，不阻断业务流转。
"""
import math

from loguru import logger

from app.core.ai.llm_client import LlmUnavailable, embed


def _cosine(a: list, b: list) -> float:
    """两向量的余弦相似度；向量为空或维度不一致返回 -1（视为不可比）"""
    if not a or not b or len(a) != len(b):
        return -1.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def _embed_batched(texts: list, chunk: int = 10) -> list:
    """分批向量化：DashScope Embedding 单次批量有条数上限，超限分批调用后按序拼接"""
    vectors = []
    for i in range(0, len(texts), chunk):
        vectors.extend(embed(texts[i:i + chunk]))
    return vectors


def dedup_match(text: str, threshold: float, candidates: list) -> dict:
    """对候选工单文本做语义相似度兜底匹配

    :param text: 待归并子诉求文本
    :param threshold: 相似度阈值（Java 侧 app.order.dedup.similarity-threshold）
    :param candidates: 候选工单列表 [{id, text}]
    :return: {matched, matched_order_id, similarity}
    """
    if not text or not candidates:
        return {"matched": False, "matched_order_id": None, "similarity": None}

    try:
        texts = [text] + [c.get("text") or "" for c in candidates]
        vectors = _embed_batched(texts)
    except LlmUnavailable:
        logger.warning("去重归并相似度兜底降级：Embedding 不可用，回退为新建工单")
        return {"matched": False, "matched_order_id": None, "similarity": None}

    if not vectors or len(vectors) != len(texts):
        return {"matched": False, "matched_order_id": None, "similarity": None}

    query = vectors[0]
    best_index = -1
    best_sim = -1.0
    for i in range(1, len(vectors)):
        sim = _cosine(query, vectors[i])
        if sim > best_sim:
            best_sim = sim
            best_index = i - 1

    if best_index >= 0 and best_sim >= threshold:
        return {
            "matched": True,
            "matched_order_id": candidates[best_index].get("id"),
            "similarity": round(best_sim, 4),
        }
    return {
        "matched": False,
        "matched_order_id": None,
        "similarity": round(best_sim, 4) if best_sim >= 0 else None,
    }