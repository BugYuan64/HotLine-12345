"""请求/响应结构体（Pydantic）：字段名与 API 接口文档 十 完全一致"""
from typing import List, Optional

from pydantic import BaseModel, Field


# ------------------------------------------------------------------ 10.1 诉求多类别检测与拆分建议
class DetectRequest(BaseModel):
    text: str = Field(..., description="诉求文本（标题+内容）")
    appeal_id: Optional[int] = Field(default=None, description="来源诉求 ID（追踪用）")


class DetectCategory(BaseModel):
    category: str = Field(..., description="URBAN/PROPERTY/LIVELIHOOD")
    confidence: float
    segment: str


class SplitSuggestion(BaseModel):
    event_type: Optional[str] = Field(default=None, description="单一事件类型（事件级拆分的归并键）")
    text: str
    clean_content: Optional[str] = Field(default=None, description="加工后诉求内容（清洗+归一化+脱敏）")
    clean_log: Optional[str] = Field(default=None, description="加工留痕（敏感词替换/PII脱敏，JSON）")


class DetectResponse(BaseModel):
    categories: List[DetectCategory] = Field(default_factory=list, description="已废弃，仅保留兼容")
    multi_category: bool = Field(default=False, description="已废弃，仅保留兼容")
    split_suggestions: List[SplitSuggestion]
    method: str = Field(default="none", description="拆分方式：llm/keyword/none")


# ------------------------------------------------------------------ 10.2 工单智能分类
class ClassifyRequest(BaseModel):
    order_id: Optional[int] = Field(default=None, description="工单 ID")
    text: str = Field(..., description="诉求文本")
    refresh: bool = Field(default=False, description="强制刷新分类知识桥 60s 缓存（仅管理端在线测试使用，真实流水线保持默认走缓存）")


class SimilarSample(BaseModel):
    sample_id: int
    similarity: float


class ClassifyResponse(BaseModel):
    primary_category: Optional[str] = Field(default=None, description="URBAN/PROPERTY/LIVELIHOOD；无法判定时为空并置 need_review=true")
    sub_category: Optional[str] = None
    department: Optional[str] = Field(default=None, description="对应职能部门（三大部门：城管/物业/民生部门）")
    confidence: float
    hit_keywords: List[str]
    similar_samples: List[SimilarSample]
    need_review: bool


# ------------------------------------------------------------------ 10.2.1 二级分类（一级已定，仅选二级标签）
class ClassifySecondaryRequest(BaseModel):
    text: str = Field(..., description="诉求文本")
    primary_category: str = Field(..., description="已确定的一级分类代码（8大类）")


class ClassifySecondaryResponse(BaseModel):
    sub_category: Optional[str] = Field(default=None, description="二级分类标签；无匹配/降级时归为'其他'")
    confidence: float
    need_review: bool
    method: str
    degraded: bool


# ------------------------------------------------------------------ 10.3 历史工单批量重分类
class ReclassifyRequest(BaseModel):
    order_ids: Optional[List[int]] = Field(default=None, description="指定工单 ID 列表（缺省为全部历史工单）")
    async_: bool = Field(default=True, alias="async", description="是否异步执行，默认 true")


class ReclassifyResponse(BaseModel):
    task_id: str
    status: str  # RUNNING / DONE
    total: int


# ------------------------------------------------------------------ 10.0 父诉求拆分子诉求并分类（一站式工作流）
class AppealProcessRequest(BaseModel):
    appeal_id: Optional[int] = Field(default=None, description="父诉求 ID（追踪用）")
    title: str = Field(..., description="父诉求标题")
    content: str = Field(..., description="父诉求完整内容")


class ChildAppealProcessed(BaseModel):
    event_type: str = Field(..., description="单一事件类型（子诉求归并键）")
    text: str = Field(..., description="子诉求文本")
    primary_category: Optional[str] = Field(default=None, description="三大类：URBAN/PROPERTY/LIVELIHOOD")
    sub_category: Optional[str] = Field(default=None, description="二级细分标签")
    department: Optional[str] = Field(default=None, description="对应职能部门")
    confidence: float
    need_review: bool


class AppealProcessResponse(BaseModel):
    appeal_id: Optional[int] = None
    multi_category: bool = Field(..., description="是否跨多个一级分类")
    children: List[ChildAppealProcessed] = Field(default_factory=list, description="拆分后的子诉求及分类结果")
    method: str = Field(..., description="拆分方法：llm/keyword/none")


# ------------------------------------------------------------------ 10.4 智能诉求引导与补全
class SuggestRequest(BaseModel):
    partial_content: str = Field(..., description="已输入部分内容")


class SuggestResponse(BaseModel):
    suggested_title: str
    completion: str
    missing_elements: List[str]


# ------------------------------------------------------------------ 10.6 高频诉求预判
class PredictRequest(BaseModel):
    history_days: Optional[int] = Field(default=None, description="历史数据周期 30/90/180/365")
    predict_period: str = Field(..., description="DAY/3_DAY/7_DAY")
    confidence_threshold: Optional[float] = None
    cluster_count: Optional[int] = None


class TrendPoint(BaseModel):
    date: str
    predicted: int
    confidence: float


class Hotspot(BaseModel):
    topic: str
    region: str
    category: str
    sub_category: Optional[str] = Field(default=None, description="二级分类标签（可为空=未区分二级）")
    predicted_count: int
    confidence: float


class PredictResponse(BaseModel):
    trend: List[TrendPoint]
    hotspots: List[Hotspot]


# ------------------------------------------------------------------ 10.7 敏感/紧急事件实时感知
class SensitiveRequest(BaseModel):
    text: str = Field(..., description="诉求文本")
    appeal_id: Optional[int] = None


class SensitiveResponse(BaseModel):
    risk_type: str  # 六大类：人身安全与公共安全/社会稳定与群体性事件/政治安全与意识形态/重大舆情风险/公职人员违纪违法/特殊人群与弱势群体权益（未命中为 none）
    risk_level: str  # GENERAL / SEVERE / URGENT
    advice: str


# ------------------------------------------------------------------ 10.11 诉求去重归并相似度兜底
class DedupCandidate(BaseModel):
    id: int = Field(..., description="候选工单 ID")
    text: str = Field(..., description="候选工单文本（标题+内容）")


class DedupRequest(BaseModel):
    text: str = Field(..., description="待归并子诉求文本")
    threshold: float = Field(default=0.85, description="相似度阈值")
    candidates: List[DedupCandidate] = Field(default_factory=list, description="候选工单列表")


class DedupResponse(BaseModel):
    matched: bool = Field(..., description="是否命中语义最相近且达阈值的候选项")
    matched_order_id: Optional[int] = Field(default=None, description="命中候选工单 ID")
    similarity: Optional[float] = Field(default=None, description="最高相似度（0~1）")


# ------------------------------------------------------------------ 10.9 预警总结生成
class WarningSummaryRequest(BaseModel):
    warning_id: int = Field(..., description="预警记录 ID")
    order_texts: List[str] = Field(..., description="关联工单文本列表")


class WarningSummaryResponse(BaseModel):
    summary: str
    confidence: float


# ------------------------------------------------------------------ 10.10 应对措施推荐
class WarningMeasureRequest(BaseModel):
    scene: str = Field(..., description="预警场景描述")


class WarningMeasureItem(BaseModel):
    measure: str
    reason: str


class WarningMeasureResponse(BaseModel):
    measures: List[WarningMeasureItem]


# ------------------------------------------------------------------ 10.12 优秀处置案例检索（预警建议 RAG）
class CaseSearchRequest(BaseModel):
    query: str = Field(..., description="检索query（命中工单标题/总结拼装的问题侧文本）")
    category: Optional[str] = Field(default=None, description="一级分类精确过滤")
    region: Optional[str] = Field(default=None, description="辖区前缀过滤")
    top_k: int = Field(default=5, description="召回数量")


class CaseSearchItem(BaseModel):
    case_no: str
    title: str
    similarity: float
    measure_plan: str
    measure_result: Optional[str] = None
    category: Optional[str] = None
    region: Optional[str] = None


class CaseSearchResponse(BaseModel):
    cases: List[CaseSearchItem]


# ------------------------------------------------------------------ 10.13 智能助手 RAG 流式问答
class ChatHistoryItem(BaseModel):
    role: str = Field(..., description="user / assistant")
    content: str


class ChatStreamRequest(BaseModel):
    question: str = Field(..., min_length=1, max_length=500, description="用户当前提问")
    history: List[ChatHistoryItem] = Field(default_factory=list, description="多轮对话历史（最近若干条）")
    scope: Optional[str] = Field(default="citizen", description="知识库作用域：citizen/staff/street/admin（一个端一个知识库）")
    # Java ChatStreamRequest.agentContext（camelCase）→ 本字段映射；populate_by_name 兼容两种键名
    agent_context: Optional[str] = Field(default=None, max_length=4096,
        alias="agentContext", populate_by_name=True,
        description="Java 签发的用户上下文令牌（AI 助手工具回调 Java /internal/agent/* 时回传）")


class ChatDocRequest(BaseModel):
    title: str = Field(..., min_length=1, max_length=100, description="知识文档标题")
    content: str = Field(..., min_length=1, max_length=5000, description="知识文档正文")
    category: Optional[str] = Field(default=None, max_length=50, description="知识分类（可选，用于过滤展示）")
    scope: Optional[str] = Field(default="citizen", description="写入的知识库作用域：citizen/staff/street")


class ChatDocResponse(BaseModel):
    doc_id: str = Field(..., description="Redis 向量库中的文档 ID")


# ------------------------------------------------------------------ 10.14 诉求标题 AI 总结（父/子诉求按内容生成标题，<=25字）
class SummarizeRequest(BaseModel):
    text: str = Field(..., description="诉求内容")


class SummarizeResponse(BaseModel):
    title: str = Field(..., description="AI 总结标题（一般不超过25字）")


# ------------------------------------------------------------------ 10.15 诉求类别 AI 审核（内容与类别是否一致）
class AuditCategoryRequest(BaseModel):
    text: str = Field(..., description="诉求内容")
    category: str = Field(..., description="类别 code（URBAN/.../OTHER）")


class AuditCategoryResponse(BaseModel):
    consistent: bool = Field(..., description="内容与类别是否一致")
    reason: str = Field(default="", description="判断依据/说明")
    keywords: List[str] = Field(default_factory=list, description="入库时分词出的关键词")