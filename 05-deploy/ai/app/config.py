"""全局配置：全部通过环境变量注入（.env.example 见项目根目录）"""
import os

from dotenv import load_dotenv

# 从 .env 文件加载环境变量（不存在时无副作用，仍走系统环境变量）
load_dotenv()

# ---- 阿里云 DashScope（通义千问，OpenAI 兼容模式）----
DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY", "")
DASHSCOPE_BASE_URL = os.getenv("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
LLM_MODEL = os.getenv("LLM_MODEL", "qwen-max")
# 衔接建议专用轻量模型（done 后生成追问建议，属低风险任务，用快模型避免拖慢流关闭）
SUGGEST_MODEL = os.getenv("SUGGEST_MODEL", "qwen-turbo")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "text-embedding-v4")
EMBEDDING_DIM = int(os.getenv("EMBEDDING_DIM", "1024"))

# ---- Redis Stack（向量库 + 与 Java 共享的分类知识桥/统计桥）----
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "") or None
REDIS_DB = int(os.getenv("REDIS_DB", "0"))
RAG_INDEX = os.getenv("RAG_INDEX", "rag_idx")
CASE_INDEX = os.getenv("CASE_INDEX", "case_idx")

# ---- 内网签名（API 文档 十、安全方案：X-Internal-Sign: HMAC-SHA256(timestamp, secret)）----
# 密钥名与 Java 端环境变量 AI_SIGN_SECRET 统一；同时兼容旧名 INTERNAL_SIGN_SECRET，均未设置时用默认值
INTERNAL_SIGN_SECRET = (os.getenv("AI_SIGN_SECRET")
                        or os.getenv("INTERNAL_SIGN_SECRET")
                        or "hotline-ai-internal-sign-secret")
SIGN_TOLERANCE_SECONDS = int(os.getenv("SIGN_TOLERANCE_SECONDS", "300"))
ALLOWED_IPS = [i.strip() for i in os.getenv("ALLOWED_IPS", "").split(",") if i.strip()]

# ---- 分类参数 ----
# 低置信度阈值：低于该值 need_review=true，工单自动进入人工复核（需求 8.3(8)）
CONFIDENCE_THRESHOLD = float(os.getenv("CONFIDENCE_THRESHOLD", "0.6"))
RAG_SIMILARITY_THRESHOLD = float(os.getenv("RAG_SIMILARITY_THRESHOLD", "0.75"))
RAG_TOP_K = int(os.getenv("RAG_TOP_K", "5"))
# ---- 三层分类架构判定阈值（L1/L2 直接判定的门槛，低于则下沉到 L3 LLM）----
# L1 关键词层：命中管理端阈值规则后还需置信度达标（0.6+权重和*0.08 公式下约需权重和>=2.5）
# 才直接判定，防弱关键词库单词低权命中锁死误判；未达标仅记录 hit_keywords 继续下沉
KEYWORD_DECIDE_THRESHOLD = float(os.getenv("KEYWORD_DECIDE_THRESHOLD", "0.8"))
# L2 向量层：top1 相似度须显著高于召回门槛(0.75)才直接判定；0.75~0.85 区间仅作溯源附带
RAG_DECIDE_THRESHOLD = float(os.getenv("RAG_DECIDE_THRESHOLD", "0.85"))
# ---- 智能助手知识库（RAG 问答，独立索引 chat_idx，与分类 rag_idx 隔离）----
CHAT_INDEX = os.getenv("CHAT_INDEX", "chat_idx")
CHAT_SIMILARITY_THRESHOLD = float(os.getenv("CHAT_SIMILARITY_THRESHOLD", "0.60"))
CHAT_TOP_K = int(os.getenv("CHAT_TOP_K", "4"))
CHAT_MAX_HISTORY = int(os.getenv("CHAT_MAX_HISTORY", "6"))  # 多轮对话携带的最大历史条数
# ---- 优秀处置案例检索参数（预警建议 RAG，独立索引 case_idx）----
CASE_SIMILARITY_THRESHOLD = float(os.getenv("CASE_SIMILARITY_THRESHOLD", "0.70"))
CASE_TOP_K = int(os.getenv("CASE_TOP_K", "5"))

# ---- Redis 分类知识桥键名（Java 业务后端在分类规则维护时写入，Python 只读）----
KEY_CLS_KEYWORDS = "cls:keywords"   # HASH field=关键词 value={"category","weight","status"}
KEY_CLS_RULES = "cls:rules"         # HASH field=一级分类 value={"hit_count_threshold","weight_sum_threshold"}
KEY_CLS_LABELS = "cls:labels"       # HASH field=一级分类 value="二级标签1,二级标签2"
KEY_CLS_PRIMARY = "cls:primary"     # HASH field=一级分类代码 value={"name","department","status"}
KEY_RAG_PENDING = "rag:pending"     # LIST：待向量化样本 {"sample_id","category","content"}
KEY_CASE_PENDING = "case:pending"   # LIST：待向量化处置案例 {"case_id","search_text","category","region",...}
# ---- 预判统计桥键名（Java 业务后端聚合工单数据写入，Python 只读，不访问业务表）----
KEY_STATS_DAILY = "stats:daily:"    # HASH field="{category}:{region}" value=当日工单数
KEY_PREDICT_TEXTS = "predict:texts" # HASH field="{category}:{region}" value=近期诉求文本(逗号分隔,截断)
KEY_RECLASSIFY_TEXTS = "reclassify:texts"  # HASH field=order_id value=工单文本
KEY_RECLASSIFY_RESULTS = "reclassify:results"  # HASH field=order_id value=分类结果JSON
# ---- AI 助手（智能助手升级：四端 AI 助手工具调用）----
# Java 业务服务内网地址：AI 助手工具回调查询业务数据（Java /internal/agent/* 端点，权限按登录用户过滤）
JAVA_BASE_URL = os.getenv("JAVA_BASE_URL", "http://localhost:8080")
# 是否启用 AI 助手工具（四端）；false 时保持原 RAG 纯知识问答
AGENT_ENABLED = os.getenv("AGENT_ENABLED", "true").lower() in ("1", "true", "yes", "on")
