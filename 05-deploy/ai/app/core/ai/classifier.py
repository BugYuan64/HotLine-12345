"""工单智能分类核心（三层级联架构，需求 8.3(4)(5)(6)）：
L1 关键词规则层（零 LLM 成本，毫秒级，管理端维护）
  → L2 向量知识库层（RAG 相似样本带出分类）
  → L3 LLM 语义分类层（PydanticAI Agent，9 类候选含 OTHER，语义泛化兜底）。

层级策略：
- L1 命中管理端阈值规则且置信度 >= KEYWORD_DECIDE_THRESHOLD → 直接判定（method=keyword）；
  低置信命中不锁死，仅记录 hit_keywords 继续下沉（防弱关键词库误判）。
- L2 top1 相似度 >= RAG_DECIDE_THRESHOLD → 直接判定（method=rag）；
  0.75~0.85 区间的相似样本仅作 similar_samples 溯源附带。
- L3 PydanticAI Agent 结构化分类，输出 Pydantic 模型校验失败自动重试。
- 外部契约（primary_category/sub_category/confidence/hit_keywords/need_review/method/degraded）不变
- AI 不可用时抛 LlmUnavailable → Java 侧回滚状态，由周期任务重试
"""
from loguru import logger

from app.config import CONFIDENCE_THRESHOLD, KEYWORD_DECIDE_THRESHOLD, RAG_DECIDE_THRESHOLD
from app.core.ai.agents import classify_agent, sub_classify_agent
from app.core.ai.llm_client import LlmUnavailable, embed
from app.core.util.keyword_classifier import keyword_decide

# 二级分类无匹配时的兜底标签
SUB_OTHER = "其他"


def _normalize_sub(sub, primary, labels) -> str:
    """二级标签归一：空或不在该一级类标签清单中 → "其他"（业务规则：没有对应二级分类时归为其他）"""
    cleaned = (sub or "").strip()
    if not cleaned:
        return SUB_OTHER
    if labels:
        v = labels.get(primary)
        subs = v if isinstance(v, list) else (str(v).split(",") if v else [])
        subs = [s.strip() for s in subs if s and s.strip()]
        if subs and cleaned not in subs and cleaned != SUB_OTHER:
            return SUB_OTHER
    return cleaned


def classify(text: str, rule_store, rag_retriever, refresh: bool = False) -> dict:
    """分类主流程（三层级联）：返回与 API 10.2 一致的响应结构。
    所有一级分类调用方共用本入口（AI一级分类/AI分类纠正/工单导入建单/批量重分类/分类效果测试），
    一级由 L1/L2/L3 之一判定；二级统一为 None，由 classify_secondary 独立阶段判定。
    refresh=True 时先强制刷新 RuleStore 60s 缓存——仅管理端「在线测试」使用，
    保证刚修改的关键词/规则立即命中；真实流水线不传，继续吃缓存节流。"""
    if refresh and rule_store is not None:
        rule_store.refresh()
    result = {
        "primary_category": None,
        "sub_category": None,
        "department": None,
        "confidence": 0.0,
        "hit_keywords": [],
        "similar_samples": [],
        "need_review": True,
        "method": "",
        "degraded": False,
    }
    department_names = rule_store.get_departments() if rule_store else {}

    def finish() -> dict:
        """统一收尾：部门映射 + 模型标识 + 结果日志（三层共用）"""
        if result["primary_category"]:
            result["department"] = department_names.get(result["primary_category"], result["primary_category"])
        result["model"] = result.get("method") + ":" + (result.get("primary_category") or "none")
        logger.info("分类结果 method={} category={} sub={} dept={} confidence={} degraded={}",
                    result["method"], result["primary_category"], result["sub_category"],
                    result["department"], result["confidence"], result["degraded"])
        return result

    # ---- L1 关键词规则层（需求 8.3(4)：优先执行，命中阈值即判定；管理端维护，零 LLM 成本）----
    decided = None
    try:
        decided = keyword_decide(text, rule_store.get_keywords(), rule_store.get_rules())
    except Exception as e:
        logger.warning("关键词层执行失败（跳过下沉）：{}", e)
    if decided:
        category, confidence, hit_list = decided
        result["hit_keywords"] = [kw for kw, _ in hit_list]
        if confidence >= KEYWORD_DECIDE_THRESHOLD:
            result.update({
                "primary_category": category,
                "sub_category": None,  # 关键词只判一级，二级由独立阶段（classify_secondary）判定
                "confidence": confidence,
                "need_review": confidence < CONFIDENCE_THRESHOLD,
                "method": "keyword",
            })
            return finish()
        # 低置信命中：不锁死判定，携带命中词下沉到 L2/L3

    # ---- L2 向量知识库层（需求 8.3(5)：RAG 相似样本带出分类）----
    similar = []
    if rag_retriever:
        try:
            vectors = embed([text])
            similar = rag_retriever.search(vectors[0]) if vectors else []
        except LlmUnavailable:
            similar = []  # Embedding 不可用：L2 跳过，L3 会再试（仍不可用则整体降级）
        except Exception as e:
            similar = []
            logger.warning("RAG 检索层异常（跳过下沉）：{}", e)
    if similar:
        result["similar_samples"] = [
            {"sample_id": s["sample_id"], "similarity": s["similarity"]} for s in similar
        ]
        top = similar[0]
        if top["similarity"] >= RAG_DECIDE_THRESHOLD and top["category"]:
            result.update({
                "primary_category": top["category"],
                "sub_category": None,  # 样本只有一级标注，二级由独立阶段判定
                "confidence": top["similarity"],
                "need_review": False,  # >= 0.85 高相似直接判定，不再标记复核
                "method": "rag",
            })
            return finish()

    # ---- L3 LLM 语义分类层（PydanticAI Agent，9 类候选含 OTHER；二级标签从 Redis 清单强制选择）----
    try:
        labels = rule_store.get_labels() if rule_store else {}
        cls = classify_agent(text, labels)
        category = cls.get("primary_category")
        # 二级标签归一：空或不在清单中 → "其他"
        sub_category = _normalize_sub(cls.get("sub_category"), category, labels)
        confidence = float(cls.get("confidence") or 0)
        if category:
            result.update({
                "primary_category": category,
                "sub_category": sub_category,
                "confidence": round(min(1.0, max(0.0, confidence)), 4),
                # 二级归一为"其他"（一级非 OTHER 时无匹配标签）→ 标记人工复核
                "need_review": confidence < CONFIDENCE_THRESHOLD
                or (sub_category == SUB_OTHER and category != "OTHER"),
                "method": "llm",
            })
        else:
            result.update({"method": "none"})  # Agent 未给出一级分类：待人工复核
    except LlmUnavailable as e:
        logger.warning("大模型不可用：{}", e)
        result["degraded"] = True
        result.update({"method": "none"})
    return finish()


def classify_secondary(text: str, primary: str, rule_store) -> dict:
    """二级分类（队列1 二级阶段）：一级分类已确定（审核/纠正/一级分类阶段结论），
    仅在 primary 对应的二级标签清单中选择 sub_category。
    一级为 OTHER（其他）时二级恒为"其他"（业务规则：其他的二级全部是其他，不调 LLM）。
    无匹配二级分类时统一归为"其他"（不在清单/LLM 无结果），need_review=True 标记人工复核；
    返回 {sub_category, confidence, need_review, method, degraded}；
    AI 不可用时抛 LlmUnavailable，由 Java 侧按降级策略处理（二级缺失不阻断流转）。"""
    result = {
        "sub_category": None,
        "confidence": 0.0,
        "need_review": True,
        "method": "",
        "degraded": False,
    }
    # 一级=其他：二级恒为"其他"（确定性规则，无需 LLM）
    if primary == "OTHER":
        result.update({
            "sub_category": "其他",
            "confidence": 1.0,
            "need_review": False,
            "method": "rule",
        })
        logger.info("二级分类结果 primary=OTHER sub=其他（规则直返）")
        return result
    labels = rule_store.get_labels() if rule_store else {}
    try:
        sub = sub_classify_agent(text, primary, labels)
        confidence = float(sub.get("confidence") or 0)
        # 二级标签归一：空或不在清单中 → "其他"，并标记人工复核
        normalized = _normalize_sub(sub.get("sub_category"), primary, labels)
        result.update({
            "sub_category": normalized,
            "confidence": round(min(1.0, max(0.0, confidence)), 4),
            "need_review": confidence < CONFIDENCE_THRESHOLD or normalized == SUB_OTHER,
            "method": "llm",
        })
    except LlmUnavailable as e:
        logger.warning("二级分类大模型不可用：{}", e)
        result["degraded"] = True
        # 降级兜底：无匹配二级分类归为"其他"（need_review 保持 True 待人工复核）
        result.update({"sub_category": SUB_OTHER, "method": "none"})
    logger.info("二级分类结果 primary={} sub={} confidence={} degraded={}",
                primary, result["sub_category"], result["confidence"], result["degraded"])
    return result