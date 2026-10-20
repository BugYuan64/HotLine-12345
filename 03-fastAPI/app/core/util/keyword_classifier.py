"""关键词权重规则匹配（需求 8.3(4)(6)：优先执行，命中阈值即判定分类）"""
from app.core.util.preprocess import clean_text


def keyword_hits(text: str, keywords: dict) -> dict:
    """返回 {category: [(keyword, weight), ...]}（按命中先后）"""
    cleaned = clean_text(text)
    hits = {}
    for kw, (category, weight) in keywords.items():
        if kw in cleaned:
            hits.setdefault(category, []).append((kw, weight))
    return hits


def keyword_decide(text: str, keywords: dict, rules: dict):
    """命中阈值判定：
    规则为「命中数量 >= hit_count_threshold 或 权重求和 >= weight_sum_threshold」，
    满足即判定为该分类；多个分类命中时取权重和最大者。
    返回 (category, confidence, [(kw, weight)...]) 或 None
    """
    hits = keyword_hits(text, keywords)
    if not hits:
        return None
    scored = []
    for category, hit_list in hits.items():
        hit_count = len(hit_list)
        weight_sum = sum(w for _, w in hit_list)
        hit_threshold, sum_threshold = rules.get(category, (1, 1.0))
        if hit_count >= hit_threshold or weight_sum >= sum_threshold:
            # 权重和映射置信度（上限 0.95），权重越高置信度越高
            confidence = round(min(0.95, 0.6 + weight_sum * 0.08), 4)
            scored.append((category, confidence, weight_sum, hit_list))
    if not scored:
        return None
    scored.sort(key=lambda x: (-x[1], -x[2]))
    category, confidence, _, hit_list = scored[0]
    return category, confidence, hit_list
