"""RAG 向量检索（需求 8.3(5)：向量库 Redis Stack，HNSW 余弦距离）

- 索引：rag_idx（HASH 前缀 rag:v:，schema：vector HNSW / category TAG / sample_id / content）
- 样本向量化入库：Java 侧维护样本时把待向量化样本推入 Redis LIST(rag:pending)，
  后台工作线程 Embedding 后写入向量索引（Python 不访问业务表）
"""
import json
import struct
import threading
import time

from loguru import logger

from app.config import RAG_INDEX, RAG_SIMILARITY_THRESHOLD, RAG_TOP_K, EMBEDDING_DIM, KEY_RAG_PENDING
from app.core.ai.llm_client import LlmUnavailable, embed


class RagRetriever:

    def __init__(self, redis_vector, redis_text):
        """初始化RAG检索器：保存Redis客户端引用，标记索引未就绪状态"""
        self.rv = redis_vector      # 二进制客户端（向量字节）
        self.rt = redis_text        # 文本客户端（JSON/键值）
        self._index_ready = False

    # ------------------------------------------------------------------ 索引
    def ensure_index(self):
        """确保Redis向量索引存在，不存在则创建HNSW余弦距离索引"""
        try:
            info = self.rt.execute_command("FT.INFO", RAG_INDEX)
            self._index_ready = True
            logger.info("向量索引 {} 已存在", RAG_INDEX)
            return
        except Exception:
            pass
        try:
            self.rt.execute_command(
                "FT.CREATE", RAG_INDEX,
                "ON", "HASH", "PREFIX", "1", "rag:v:",
                "SCHEMA",
                "vector", "VECTOR", "HNSW", "6",
                "TYPE", "FLOAT32", "DIM", EMBEDDING_DIM, "DISTANCE_METRIC", "COSINE",
                "category", "TAG",
                "sample_id", "TEXT",
                "content", "TEXT",
            )
            self._index_ready = True
            logger.info("向量索引 {} 创建成功（dim={}）", RAG_INDEX, EMBEDDING_DIM)
        except Exception as e:
            # 非 Redis Stack 或索引创建失败：RAG 环节自动降级，不影响关键词分类
            logger.warning("向量索引创建失败，RAG 检索降级：{}", e)

    # ------------------------------------------------------------------ 写入
    def add_sample(self, sample_id: int, category: str, content: str, vector: list):
        """向向量索引中添加单个样本（序列化向量为二进制存储）"""
        if not self._index_ready:
            return
        key = f"rag:v:{sample_id}"
        try:
            self.rv.hset(key, mapping={
                "vector": struct.pack(f"{len(vector)}f", *vector),
                "category": category,
                "sample_id": str(sample_id),
                "content": content,
            })
        except Exception as e:
            logger.warning("样本 {} 向量入库失败：{}", sample_id, e)

    # ------------------------------------------------------------------ 检索
    def search(self, vector: list, k: int = None) -> list:
        """返回 [{sample_id, category, content, similarity}]（按相似度降序）"""
        if not self._index_ready or not vector:
            return []
        k = k or RAG_TOP_K
        try:
            blob = struct.pack(f"{len(vector)}f", *vector)
            rows = self.rv.execute_command(
                "FT.SEARCH", RAG_INDEX,
                f"*=>[KNN {k} @vector $v AS score]",
                "PARAMS", "2", "v", blob,
                "RETURN", "4", "sample_id", "category", "content", "score",
                "SORTBY", "score", "ASC",
                "DIALECT", "2",
            )
            results = []
            for i in range(1, len(rows), 2):
                # 二进制客户端（decode_responses=False）返回的键值均为 bytes，
                # 须先解码为 str 再取值，否则 str 键查 bytes dict 恒取默认值导致检索静默失效
                fields = rows[i + 1] if i + 1 < len(rows) else []
                doc = {}
                for j in range(0, len(fields) - 1, 2):
                    key = fields[j].decode() if isinstance(fields[j], bytes) else str(fields[j])
                    val = fields[j + 1].decode() if isinstance(fields[j + 1], bytes) else fields[j + 1]
                    doc[key] = val
                try:
                    similarity = 1.0 - float(doc.get("score", 1))
                except (TypeError, ValueError):
                    similarity = 0.0
                if similarity < RAG_SIMILARITY_THRESHOLD:
                    continue
                try:
                    sample_id = int(doc.get("sample_id", 0))
                except (TypeError, ValueError):
                    sample_id = 0
                results.append({
                    "sample_id": sample_id,
                    "category": doc.get("category", ""),
                    "content": doc.get("content", ""),
                    "similarity": round(similarity, 4),
                })
            return results
        except Exception as e:
            logger.warning("向量检索失败，RAG 环节降级：{}", e)
            return []

    # ------------------------------------------------------------------ 待向量化样本后台处理
    def start_pending_worker(self):
        """后台线程：消费 rag:pending 队列，Embedding 后写入向量索引"""

        def worker():
            """后台工作线程：循环消费待向量化队列，Embedding后入库，不可用时回退重试"""
            while True:
                try:
                    item = self.rt.blpop(KEY_RAG_PENDING, timeout=10)
                    if not item:
                        continue
                    meta = json.loads(item[1])
                    vectors = embed([meta["content"]])
                    if vectors:
                        self.add_sample(int(meta["sample_id"]), meta["category"], meta["content"], vectors[0])
                        logger.info("样本 {} 向量化入库完成", meta["sample_id"])
                except LlmUnavailable:
                    # 无 Embedding 能力：样本暂存回队列头部，等待配置密钥后重试
                    try:
                        self.rt.lpush(KEY_RAG_PENDING, item[1])
                    except Exception:
                        pass
                    time.sleep(30)
                except Exception as e:
                    # Redis 抖动等异常：退避 5 秒再重试，避免无间隔空转打爆日志
                    logger.warning("样本向量化任务异常：{}", e)
                    time.sleep(5)

        t = threading.Thread(target=worker, name="rag-pending-worker", daemon=True)
        t.start()
        logger.info("待向量化样本后台线程已启动")
