"""高频诉求预判（需求 9(3) / API 10.6）：时序预测 + 关键词热点聚类

数据来源：Java 业务后端聚合工单统计后写入 Redis Stack 统计桥（Python 不访问业务表）——
  stats:daily:{yyyyMMdd}    HASH  field="{category}:{sub_category}:{region}"  value=当日工单数
  predict:texts             HASH  field="{category}:{sub_category}:{region}"  value=近期诉求文本
预测算法：移动平均 + 线性趋势外推（numpy 最小二乘），置信度与数据量/波动负相关；
热点识别：预测量 Top-K（cluster_count）的 (分类, 二级分类, 辖区) 组合 + 近期文本高频词聚类主题。
"""
from collections import Counter, defaultdict
from datetime import datetime, timedelta

import jieba
import numpy as np
from loguru import logger

from app.core.util.preprocess import STOPWORDS, clean_text

PERIOD_DAYS = {"DAY": 1, "3_DAY": 3, "7_DAY": 7}


def _series_predict(counts: list) -> tuple:
    """单序列预测：线性趋势外推 + 移动平均，返回 (period 预测数, 置信度)"""
    if not counts:
        return 0, 0.0
    if len(counts) < 3:
        base = float(np.mean(counts))
        confidence = 0.4
    else:
        y = np.asarray(counts[-30:], dtype=float)  # 最多取最近30个点
        x = np.arange(len(y), dtype=float)
        slope, intercept = np.polyfit(x, y, 1)
        # 最近7天移动平均为基线，叠加斜率趋势
        base = float(np.mean(y[-7:]))
        base = base * (1 + max(-0.3, min(0.3, slope / max(base, 1))))
        # 波动越大置信度越低
        cv = float(np.std(y)) / max(float(np.mean(y)), 1.0)
        confidence = round(min(0.9, max(0.35, 0.9 - cv * 0.4)), 4)
    return int(round(max(0, base))), confidence


def predict(redis_text, history_days: int = 90, predict_period: str = "7_DAY",
            confidence_threshold: float = 0.7, cluster_count: int = 10,
            category_names: dict | None = None) -> dict:
    """返回与 API 10.6 一致的响应结构"""
    history_days = history_days or 90
    # 周期钳制：防御异常入参（如超大值导致逐日 Redis 读过慢），文档建议 30/90/180/365
    history_days = max(1, min(history_days, 730))
    if predict_period not in PERIOD_DAYS:
        predict_period = "7_DAY"
    period_days = PERIOD_DAYS[predict_period]
    today = datetime.now().date()

    # ---- 1. 读取统计桥，构建 (category, sub_category, region) 时间序列 ----
    series = defaultdict(list)  # (cat, sub, region) -> [count, ...] 按日期升序
    for i in range(history_days - 1, -1, -1):
        d = today - timedelta(days=i)
        try:
            daily = redis_text.hgetall(f"stats:daily:{d:%Y%m%d}") or {}
        except Exception as e:
            logger.warning("读取统计桥失败：{}", e)
            daily = {}
        for field, value in daily.items():
            parts = field.split(":", 2)
            if len(parts) != 3:
                continue
            category, sub_category, region = parts
            try:
                series[(category, sub_category, region)].append(int(value))
            except (TypeError, ValueError):
                series[(category, sub_category, region)].append(0)
        # 补齐缺失日期（无记录=0）
        for key in list(series.keys()):
            if len(series[key]) < history_days - i:
                series[key] = [0] * (history_days - i - len(series[key])) + series[key]

    # ---- 2. 各序列周期预测（_series_predict 返回日均值 × 周期天数 = 周期内预测总量）----
    # 文档约定：趋势按周期总量均分到逐日、hotspot.predicted_count 为该组合未来周期预测总数
    per_series = {}  # (cat, sub, region) -> (period_predicted, confidence)
    for key, counts in series.items():
        daily_predicted, confidence = _series_predict(counts)
        if confidence >= (confidence_threshold or 0):
            per_series[key] = (daily_predicted * period_days, confidence)

    # ---- 3. 趋势：未来 period_days 天逐日总量 ----
    total_predicted = sum(p for p, _ in per_series.values())
    avg_conf = (sum(c for _, c in per_series.values()) / len(per_series)) if per_series else 0.0
    trend = []
    for i in range(1, period_days + 1):
        d = today + timedelta(days=i)
        daily_count = int(round(total_predicted / period_days)) if per_series else 0
        trend.append({
            "date": d.strftime("%Y-%m-%d"),
            "predicted": daily_count,
            "confidence": round(avg_conf, 4),
        })

    # ---- 4. 热点：预测量 Top-K + 近期文本高频词主题 ----
    top_keys = sorted(per_series.items(), key=lambda kv: -kv[1][0])[: max(1, min(cluster_count or 10, 50))]
    hotspots = []
    for (category, sub_category, region), (predicted_count, confidence) in top_keys:
        topic = _build_topic(redis_text, category, sub_category, region, category_names)
        hotspots.append({
            "topic": topic,
            "region": region,
            "category": category,
            "sub_category": sub_category or None,
            "predicted_count": predicted_count,
            "confidence": confidence,
        })
    logger.info("预判完成：周期={} 序列数={} 热点数={}", predict_period, len(per_series), len(hotspots))
    return {"trend": trend, "hotspots": hotspots}


def _build_topic(redis_text, category: str, sub_category: str, region: str, category_names: dict | None = None) -> str:
    """热点主题：近期诉求文本高频词聚类（取 Top3 关键词）；分类名称从 cls:primary 动态读取"""
    key = f"{category}:{sub_category or ''}:{region}"
    try:
        raw = redis_text.hget("predict:texts", key)
        if raw:
            texts = raw.split("||")
            counter = Counter()
            for t in texts[:200]:
                for w in jieba.cut(clean_text(t)):
                    if w and len(w) >= 2 and w not in STOPWORDS:
                        counter[w] += 1
            top_words = [w for w, _ in counter.most_common(3)]
            if top_words:
                return "/".join(top_words)
    except Exception as e:
        logger.warning("热点主题文本聚类失败：{}", e)
    names = category_names or {}
    label = f"{names.get(category, category)}" + (f"-{sub_category}" if sub_category else "")
    return f"{region}{label}类诉求集中爆发"
