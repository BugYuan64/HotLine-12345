"""优秀处置案例向量检索（预警建议 RAG 检索来源）

- 索引：case_idx（HASH 前缀 case:v:，schema：vector HNSW / category TAG / region TAG /
  case_no / title / content / measure_plan / measure_result）
- 案例向量化入库：Java 侧维护案例时把待向量化案例推入 Redis LIST(case:pending)，
  后台工作线程 Embedding 后写入向量索引（Python 不访问业务表）
- 与分类索引 rag_idx 完全隔离（独立索引/前缀/队列），互不影响
"""
import json
import struct
import threading
import time

from loguru import logger

from app.config import CASE_INDEX, CASE_SIMILARITY_THRESHOLD, CASE_TOP_K, EMBEDDING_DIM, KEY_CASE_PENDING
from app.core.ai.llm_client import LlmUnavailable, embed


class CaseRetriever:

    def __init__(self, redis_vector, redis_text):
        """初始化案例检索器：保存Redis客户端引用，标记索引未就绪状态"""
        self.rv = redis_vector      # 二进制客户端（向量字节）
        self.rt = redis_text        # 文本客户端（JSON/键值）
        self._index_ready = False

    # ------------------------------------------------------------------ 索引
    def ensure_index(self):
        """确保案例向量索引存在，不存在则创建HNSW余弦距离索引"""
        try:
            info = self.rt.execute_command("FT.INFO", CASE_INDEX)
            self._index_ready = True
            logger.info("案例向量索引 {} 已存在", CASE_INDEX)
            return
        except Exception:
            pass
        try:
            self.rt.execute_command(
                "FT.CREATE", CASE_INDEX,
                "ON", "HASH", "PREFIX", "1", "case:v:",
                "SCHEMA",
                "vector", "VECTOR", "HNSW", "6",
                "TYPE", "FLOAT32", "DIM", EMBEDDING_DIM, "DISTANCE_METRIC", "COSINE",
                "category", "TAG",
                "region", "TAG",
                "case_no", "TEXT",
                "title", "TEXT",
                "content", "TEXT",
                "measure_plan", "TEXT",
                "measure_result", "TEXT",
            )
            self._index_ready = True
            logger.info("案例向量索引 {} 创建成功（dim={}）", CASE_INDEX, EMBEDDING_DIM)
        except Exception as e:
            logger.warning("案例向量索引创建失败，案例 RAG 检索降级：{}", e)

    # ------------------------------------------------------------------ 写入
    def add_case(self, case_id: int, case_no: str, title: str, category: str, region: str,
                 content: str, measure_plan: str, measure_result: str, vector: list):
        """向案例向量索引中添加单个案例（序列化向量为二进制存储）"""
        if not self._index_ready:
            return
        key = f"case:v:{case_id}"
        try:
            self.rv.hset(key, mapping={
                "vector": struct.pack(f"{len(vector)}f", *vector),
                "case_no": case_no or "",
                "category": category or "",
                "region": region or "",
                "title": title,
                "content": content,
                "measure_plan": measure_plan,
                "measure_result": measure_result or "",
            })
        except Exception as e:
            logger.warning("案例 {} 向量入库失败：{}", case_id, e)

    # ------------------------------------------------------------------ 检索
    def search(self, vector: list, category: str = None, region: str = None, k: int = None) -> list:
        """返回 [{case_no,title,similarity,measure_plan,measure_result,category,region}]（按相似度降序）"""
        if not self._index_ready or not vector:
            return []
        k = k or CASE_TOP_K
        # 构造预过滤：先辖区（前缀）+ 一级分类（精确）过滤，再在范围内做 KNN，避免跨类别/跨辖区案例混入
        filters = []
        if category:
            filters.append(f"@category:{{{category}}}")
        if region:
            filters.append(f"@region:{{{region}*}}")
        if filters:
            query = "(" + " ".join(filters) + f") =>[KNN {k} @vector $v AS score]"
        else:
            query = f"*=>[KNN {k} @vector $v AS score]"
        try:
            blob = struct.pack(f"{len(vector)}f", *vector)
            rows = self.rv.execute_command(
                "FT.SEARCH", CASE_INDEX, query,
                "PARAMS", "2", "v", blob,
                "RETURN", "7", "case_no", "title", "measure_plan", "measure_result", "category", "region", "score",
                "SORTBY", "score", "ASC",
                "DIALECT", "2",
            )
            results = []
            for i in range(1, len(rows), 2):
                # 二进制客户端（decode_responses=False）返回的键值均为 bytes，须先解码为 str 再取值
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
                if similarity < CASE_SIMILARITY_THRESHOLD:
                    continue
                results.append({
                    "case_no": doc.get("case_no", ""),
                    "title": doc.get("title", ""),
                    "similarity": round(similarity, 4),
                    "measure_plan": doc.get("measure_plan", ""),
                    "measure_result": doc.get("measure_result", ""),
                    "category": doc.get("category", ""),
                    "region": doc.get("region", ""),
                })
            return results
        except Exception as e:
            logger.warning("案例向量检索失败，案例 RAG 环节降级：{}", e)
            return []

    # ------------------------------------------------------------------ 待向量化案例后台处理
    def start_pending_worker(self):
        """后台线程：消费 case:pending 队列，Embedding 后写入案例向量索引"""

        def worker():
            """后台工作线程：循环消费待向量化队列，Embedding后入库，不可用时回退重试"""
            while True:
                try:
                    item = self.rt.blpop(KEY_CASE_PENDING, timeout=10)
                    if not item:
                        continue
                    meta = json.loads(item[1])
                    vectors = embed([meta["search_text"]])
                    if vectors:
                        self.add_case(int(meta["case_id"]), meta.get("case_no", ""), meta.get("title", ""), meta.get("category"),
                                      meta.get("region"), meta.get("content", ""),
                                      meta.get("measure_plan", ""), meta.get("measure_result", ""),
                                      vectors[0])
                        logger.info("案例 {} 向量化入库完成", meta["case_id"])
                except LlmUnavailable:
                    try:
                        self.rt.lpush(KEY_CASE_PENDING, item[1])
                    except Exception:
                        pass
                    time.sleep(30)
                except Exception as e:
                    logger.warning("案例向量化任务异常：{}", e)
                    time.sleep(5)

        t = threading.Thread(target=worker, name="case-pending-worker", daemon=True)
        t.start()
        logger.info("待向量化案例后台线程已启动")