"""AI 推理接口（API 接口文档 十）：/ai/v1/ 仅供 Java 业务服务内网调用（签名校验）"""
from fastapi import APIRouter, Depends, HTTPException, Request
from loguru import logger

from app.routers.auth import verify_internal
from app.core.ai.classifier import classify, classify_secondary
from app.core.ai.deduper import dedup_match
from app.core.ai.detector import detect
from app.core.ai.llm_client import LlmUnavailable, embed
from app.core.ai.predictor import predict
from app.core.ai.reclassify import reclassify
from app.core.ai.sensitive_detector import detect_sensitive
from app.core.ai.suggester import suggest
from app.core.ai.title_summarizer import summarize_title
from app.core.ai.category_auditor import audit_category
from app.core.ai.warning_assistant import recommend_measures, summarize_warning
from app.schemas import (
    AppealProcessRequest,
    AppealProcessResponse,
    CaseSearchItem,
    CaseSearchRequest,
    CaseSearchResponse,
    ClassifyRequest,
    ClassifyResponse,
    ClassifySecondaryRequest,
    ClassifySecondaryResponse,
    DedupRequest,
    DedupResponse,
    DetectRequest,
    DetectResponse,
    PredictRequest,
    PredictResponse,
    ReclassifyRequest,
    ReclassifyResponse,
    SensitiveRequest,
    SensitiveResponse,
    SuggestRequest,
    SuggestResponse,
    WarningMeasureRequest,
    WarningMeasureResponse,
    WarningSummaryRequest,
    WarningSummaryResponse,
    SummarizeRequest,
    SummarizeResponse,
    AuditCategoryRequest,
    AuditCategoryResponse,
)

router = APIRouter(prefix="/ai/v1", dependencies=[Depends(verify_internal)])


@router.get("/health")
async def health():
    """连通性探测（Java 启动/重连探针用，无需业务逻辑）"""
    return {"status": "ok"}


def _context(request: Request):
    """从 app.state 取出共享组件（main.py lifespan 初始化）"""
    return (request.app.state.rule_store, request.app.state.rag_retriever,
            request.app.state.redis_text)


# ------------------------------------------------------------------ 10.1 诉求多类别检测与拆分建议
@router.post("/detect", response_model=DetectResponse)
def detect_endpoint(req: DetectRequest, request: Request):
    """诉求多类别检测与拆分建议接口"""
    result = detect(req.text)
    logger.info("多类别检测 appeal_id={} method={} multi={}", req.appeal_id,
                result.get("method"), result.get("multi_category"))
    return DetectResponse(**result)


# ------------------------------------------------------------------ 10.0 父诉求拆分子诉求并分类（一站式工作流）
@router.post("/appeal-process", response_model=AppealProcessResponse)
def appeal_process_endpoint(req: AppealProcessRequest, request: Request):
    """父诉求拆分子诉求并分类：
    1. 父诉求按独立事件拆分为多个子诉求（detect）
    2. 每个子诉求进入核心分类系统：先分三大类（对应三大部门），再二级细分（classify）
    一站式返回拆分后的子诉求及其分类结果，供 Java 侧包装为工单后进入分派流程。
    """
    rule_store, rag_retriever, _ = _context(request)

    # 1. 父诉求拆分（标题+内容拼接，保证拆分覆盖标题中的事件信息）
    full_text = req.title + "。" + req.content if req.title else req.content
    detect_result = detect(full_text)

    # 2. 对每个拆分的子诉求执行工单分类（三大类→二级细分）
    children = []
    for split in detect_result.get("split_suggestions") or []:
        child_text = split.get("text") or full_text
        cls = classify(child_text, rule_store, rag_retriever)
        children.append({
            "event_type": split.get("event_type") or "",
            "text": child_text,
            "primary_category": cls.get("primary_category"),
            "sub_category": cls.get("sub_category"),
            "department": cls.get("department"),
            "confidence": cls.get("confidence", 0.0),
            "need_review": cls.get("need_review", True),
        })

    result = {
        "appeal_id": req.appeal_id,
        "multi_category": len(children) > 1,
        "children": children,
        "method": detect_result.get("method", "none"),
    }
    logger.info("父诉求拆分分类 appeal_id={} method={} children={} multi={}",
                req.appeal_id, result["method"], len(children), result["multi_category"])
    return AppealProcessResponse(**result)


# ------------------------------------------------------------------ 10.2 工单智能分类
@router.post("/classify", response_model=ClassifyResponse)
def classify_endpoint(req: ClassifyRequest, request: Request):
    """工单智能分类接口（三层级联：关键词→向量→LLM；refresh 仅管理端在线测试强制刷新知识桥缓存）"""
    rule_store, rag_retriever, _ = _context(request)
    try:
        result = classify(req.text, rule_store, rag_retriever, refresh=req.refresh)
    except LlmUnavailable as e:
        logger.warning("分类降级：{}", e)
        raise HTTPException(status_code=503, detail="AI服务暂不可用")
    return ClassifyResponse(**result)


# ------------------------------------------------------------------ 10.2.1 二级分类（一级已定，仅选二级标签）
@router.post("/classify_secondary", response_model=ClassifySecondaryResponse)
def classify_secondary_endpoint(req: ClassifySecondaryRequest, request: Request):
    """二级分类接口：一级分类已由审核/纠正/一级分类阶段确定，仅在指定一级类下选二级标签。
    无匹配二级分类时归为"其他"（AI 降级时 degraded=true + sub_category="其他"，Java 侧按降级策略继续流转）。"""
    rule_store, _, _ = _context(request)
    try:
        result = classify_secondary(req.text, req.primary_category, rule_store)
    except LlmUnavailable as e:
        logger.warning("二级分类降级：{}", e)
        raise HTTPException(status_code=503, detail="AI服务暂不可用")
    return ClassifySecondaryResponse(**result)


# ------------------------------------------------------------------ 10.3 历史工单批量重分类
@router.post("/reclassify", response_model=ReclassifyResponse)
def reclassify_endpoint(req: ReclassifyRequest, request: Request):
    """历史工单批量重分类接口：支持同步或异步执行"""
    rule_store, rag_retriever, redis_text = _context(request)
    result = reclassify(req.order_ids, req.async_, redis_text, rule_store, rag_retriever)
    return ReclassifyResponse(**result)


# ------------------------------------------------------------------ 10.4 智能诉求引导与补全
@router.post("/suggest", response_model=SuggestResponse)
def suggest_endpoint(req: SuggestRequest):
    """智能诉求引导与补全接口：推荐标题、补全内容、提示缺失要素"""
    try:
        return SuggestResponse(**suggest(req.partial_content))
    except LlmUnavailable as e:
        # 大模型不可用：返回 503，由 Java 侧按「AI 降级特殊响应」处理（code 200 + ai_degraded）
        logger.warning("诉求引导补全降级：{}", e)
        raise HTTPException(status_code=503, detail="AI服务暂不可用")


# ------------------------------------------------------------------ 10.6 高频诉求预判
@router.post("/predict", response_model=PredictResponse)
def predict_endpoint(req: PredictRequest, request: Request):
    """高频诉求预判接口：时序预测趋势+热点聚类识别"""
    rule_store, _, redis_text = _context(request)
    result = predict(
        redis_text,
        history_days=req.history_days or 90,
        predict_period=req.predict_period,
        confidence_threshold=req.confidence_threshold or 0.7,
        cluster_count=req.cluster_count or 10,
        category_names=rule_store.get_categories(),
    )
    return PredictResponse(**result)


# ------------------------------------------------------------------ 10.7 敏感/紧急事件实时感知
@router.post("/sensitive", response_model=SensitiveResponse)
def sensitive_endpoint(req: SensitiveRequest):
    """敏感/紧急事件实时感知接口：仅命中六大类情形（人身安全与公共安全/社会稳定与群体性事件/
    政治安全与意识形态/重大舆情风险/公职人员违纪违法/特殊人群与弱势群体权益）才触发"""
    try:
        result = detect_sensitive(req.text)
        logger.info("敏感事件感知 appeal_id={} risk_type={} level={}", req.appeal_id,
                    result["risk_type"], result["risk_level"])
        return SensitiveResponse(**result)
    except LlmUnavailable as e:
        logger.warning("敏感事件感知降级：{}", e)
        raise HTTPException(status_code=503, detail="AI服务暂不可用")


# ------------------------------------------------------------------ 10.11 诉求去重归并相似度兜底
@router.post("/dedup", response_model=DedupResponse)
def dedup_endpoint(req: DedupRequest):
    """诉求去重归并相似度兜底：对候选工单文本做Embedding余弦相似度比对，返回最相似命中项"""
    result = dedup_match(req.text, req.threshold, [c.model_dump() for c in req.candidates])
    logger.info("去重归并 candidate_count={} matched={} similarity={}", len(req.candidates),
                result["matched"], result.get("similarity"))
    return DedupResponse(**result)


# ------------------------------------------------------------------ 10.9 预警总结生成
@router.post("/warning/summary", response_model=WarningSummaryResponse)
def warning_summary_endpoint(req: WarningSummaryRequest):
    """预警总结生成接口：基于关联工单生成预警总结与置信度"""
    try:
        result = summarize_warning(req.warning_id, req.order_texts)
        logger.info("预警总结 warning_id={} summary_len={}", req.warning_id, len(result.get("summary", "")))
        return WarningSummaryResponse(**result)
    except LlmUnavailable as e:
        logger.warning("预警总结降级：{}", e)
        raise HTTPException(status_code=503, detail="AI服务暂不可用")


# ------------------------------------------------------------------ 10.10 应对措施推荐
@router.post("/warning/measure", response_model=WarningMeasureResponse)
def warning_measure_endpoint(req: WarningMeasureRequest):
    """应对措施推荐接口：根据预警场景给出处置措施与理由"""
    try:
        return WarningMeasureResponse(**recommend_measures(req.scene))
    except LlmUnavailable as e:
        logger.warning("应对措施推荐降级：{}", e)
        raise HTTPException(status_code=503, detail="AI服务暂不可用")


# ------------------------------------------------------------------ 10.12 优秀处置案例检索（预警建议 RAG）
@router.post("/warning/cases/search", response_model=CaseSearchResponse)
def warning_case_search_endpoint(req: CaseSearchRequest, request: Request):
    """优秀处置案例检索接口：根据问题文本+分类+辖区召回相似案例（预警建议 RAG 来源）"""
    case_retriever = request.app.state.case_retriever
    try:
        vectors = embed([req.query])
    except LlmUnavailable as e:
        # 案例 RAG 为最佳努力增强：Embedding 不可用降级为空召回，不阻断预警措施生成
        logger.warning("处置案例检索 Embedding 降级：{}", e)
        return CaseSearchResponse(cases=[])
    items = case_retriever.search(vectors[0], req.category, req.region, req.top_k)
    logger.info("处置案例检索 top_k={} matched={} category={}", req.top_k, len(items), req.category)
    return CaseSearchResponse(cases=[CaseSearchItem(**i) for i in items])


# ------------------------------------------------------------------ 10.14 诉求标题 AI 总结（父/子诉求按内容生成标题，<=25字）
@router.post("/summarize", response_model=SummarizeResponse)
def summarize_endpoint(req: SummarizeRequest):
    """诉求内容 AI 总结标题接口（市民提交诉求/拆分子诉求后生成标题入库）"""
    try:
        result = summarize_title(req.text)
    except LlmUnavailable as e:
        logger.warning("标题总结降级：{}", e)
        raise HTTPException(status_code=503, detail="AI服务暂不可用")
    return SummarizeResponse(**result)


# ------------------------------------------------------------------ 10.15 诉求类别 AI 审核（内容与类别是否一致）
@router.post("/audit-category", response_model=AuditCategoryResponse)
def audit_category_endpoint(req: AuditCategoryRequest):
    """AI 审核诉求内容与其类别是否一致；不一致由 Java 侧进入 AI 分类纠正"""
    try:
        result = audit_category(req.text, req.category)
    except LlmUnavailable as e:
        logger.warning("类别审核降级：{}", e)
        raise HTTPException(status_code=503, detail="AI服务暂不可用")
    return AuditCategoryResponse(**result)