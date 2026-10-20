"""历史工单批量重分类（需求 8.3(13) 引擎调度 / API 10.3）

工单文本由 Java 业务后端触发时写入 Redis 桥（reclassify:texts HASH），
本服务执行分类后把结果写回 reclassify:results HASH，由 Java 侧应用结果。
Python 服务不访问任何业务数据库表。
"""
import json
import random
import string
import threading
from datetime import datetime

from loguru import logger

from app.config import KEY_RECLASSIFY_RESULTS, KEY_RECLASSIFY_TEXTS
from app.core.ai.classifier import classify


def _new_task_id() -> str:
    """生成重分类任务ID：时间戳+随机后缀组合"""
    suffix = "".join(random.choices(string.ascii_uppercase + string.digits, k=6))
    return f"RC{datetime.now():%Y%m%d%H%M%S}{suffix}"


def reclassify(order_ids, run_async: bool, redis_text, rule_store, rag_retriever) -> dict:
    """执行批量重分类；order_ids 为空时取桥内全部文本"""
    task_id = _new_task_id()
    texts = {}
    try:
        raw = redis_text.hgetall(KEY_RECLASSIFY_TEXTS) or {}
        for k, v in raw.items():
            try:
                texts[int(k)] = v
            except (TypeError, ValueError):
                # 单条脏数据（非数字 key）跳过，不影响其余工单的重分类
                continue
    except Exception as e:
        logger.warning("读取重分类桥失败：{}", e)

    if order_ids:
        texts = {oid: texts[oid] for oid in order_ids if oid in texts}
    total = len(texts)
    logger.info("批量重分类任务 {} 启动：共 {} 条", task_id, total)

    def run():
        """重分类执行循环：遍历工单文本逐条分类并写入结果桥"""
        for oid, text in texts.items():
            try:
                result = classify(text, rule_store, rag_retriever)
                redis_text.hset(KEY_RECLASSIFY_RESULTS, str(oid), json.dumps(result, ensure_ascii=False))
            except Exception as e:
                logger.warning("重分类工单 {} 失败：{}", oid, e)
        logger.info("批量重分类任务 {} 完成", task_id)

    if run_async:
        threading.Thread(target=run, name=f"reclassify-{task_id}", daemon=True).start()
        return {"task_id": task_id, "status": "RUNNING", "total": total}
    run()
    return {"task_id": task_id, "status": "DONE", "total": total}
