"""12345政务热线 — Python AI/RAG 独立服务入口

仅提供 /ai/v1/ 推理接口（IP白名单+内网签名），不操作任何业务数据库表。
启动：uvicorn app.main:app --host 0.0.0.0 --port 8000
"""
from contextlib import asynccontextmanager

import redis
from fastapi import FastAPI
from loguru import logger

from app.config import REDIS_DB, REDIS_HOST, REDIS_PASSWORD, REDIS_PORT
from app.core.ai.case_retriever import CaseRetriever
from app.core.ai.chat_kb import ChatKnowledgeBase
from app.core.ai.rag_retriever import RagRetriever
from app.core.util.rule_store import RuleStore
from app.routers.ai_router import router as ai_router
from app.routers.chat_router import router as chat_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理：初始化Redis客户端、规则存储和RAG检索器，启动时加载资源，关闭时释放连接"""
    # 文本客户端（JSON/键值）与二进制客户端（向量字节）
    # socket_timeout=30 覆盖 blpop 的 10s 阻塞读；health_check_interval=30 自动探活并重连坏连接
    redis_text = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, password=REDIS_PASSWORD,
                             db=REDIS_DB, decode_responses=True,
                             socket_timeout=30, socket_connect_timeout=5, health_check_interval=30)
    redis_vector = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, password=REDIS_PASSWORD,
                               db=REDIS_DB, decode_responses=False,
                               socket_timeout=30, socket_connect_timeout=5, health_check_interval=30)
    # Redis 不可用时持续重连，直到连接成功才继续启动（未就绪不对外提供 AI 服务）
    import time as _time
    while True:
        try:
            redis_text.ping()
            logger.info("Redis 连接成功：{}:{}", REDIS_HOST, REDIS_PORT)
            break
        except Exception as e:
            logger.warning("Redis 未就绪（{}:{}）：{}，5 秒后重试直到连接成功", REDIS_HOST, REDIS_PORT, e)
            _time.sleep(5)
    app.state.redis_text = redis_text
    app.state.rule_store = RuleStore(redis_text)
    app.state.rag_retriever = RagRetriever(redis_vector, redis_text)
    app.state.rag_retriever.ensure_index()
    app.state.rag_retriever.start_pending_worker()
    # 优秀处置案例向量检索器（预警建议 RAG）：独立索引 case_idx，与分类 rag_idx 完全隔离
    app.state.case_retriever = CaseRetriever(redis_vector, redis_text)
    app.state.case_retriever.ensure_index()
    app.state.case_retriever.start_pending_worker()
    # 智能助手知识库（RAG 流式问答）：一个端一个知识库（scope 隔离）
    # citizen 市民端 / staff 部门端 / street 街道办端 / admin 管理端，各自独立索引与内置知识
    for scope in ("citizen", "staff", "street", "admin"):
        kb = ChatKnowledgeBase(redis_vector, redis_text, scope=scope)
        kb.ensure_index()
        kb.seed_default_if_empty()
        setattr(app.state, f"chat_kb_{scope}", kb)
    logger.info("AI 服务启动完成：Redis {}:{} / 向量索引 rag_idx、case_idx、chat_citizen/staff/street/admin_idx",
                REDIS_HOST, REDIS_PORT)
    yield
    redis_text.close()
    redis_vector.close()


app = FastAPI(title="12345政务热线 AI 服务", version="1.0.0", lifespan=lifespan)
app.include_router(ai_router)
app.include_router(chat_router)
