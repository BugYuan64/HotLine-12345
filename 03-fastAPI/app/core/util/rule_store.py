"""分类知识桥（架构隔离：Python 不操作业务表，仅读 Redis Stack 共享存储）

Java 业务后端在「分类规则维护」模块增删改一级分类/二级标签/关键词/规则时，
同步写入 Redis Stack，本模块读取并做 60 秒进程内缓存。
Redis 无数据或异常时返回空数据（AI 无法判定，need_review=true），不再内置硬编码兜底。
一级分类名称与分派部门从 Redis cls:primary 读取。
"""
import json
import time

from app.config import KEY_CLS_KEYWORDS, KEY_CLS_LABELS, KEY_CLS_RULES, KEY_CLS_PRIMARY

_CACHE_TTL = 60


class RuleStore:
    """关键词库/规则/标签/一级分类读取（仅读 Redis，60s 缓存，无内置兜底）"""

    def __init__(self, redis_client):
        """初始化规则存储：保存Redis客户端引用，初始化缓存与加载时间戳"""
        self.r = redis_client
        self._cache = {}
        self._load_at = 0.0

    def _load(self):
        """加载分类规则数据：仅从 Redis 读取，60 秒缓存；Redis 空/异常返回空数据"""
        now = time.time()
        if now - self._load_at < _CACHE_TTL and self._cache:
            return self._cache
        keywords = {}
        rules = {}
        labels = {}
        category_names = {}
        department_names = {}
        try:
            raw_kw = self.r.hgetall(KEY_CLS_KEYWORDS)
            if raw_kw:
                for kw, val in raw_kw.items():
                    try:
                        meta = json.loads(val)
                        if meta.get("status", "ENABLED") == "ENABLED":
                            keywords[kw] = (meta["category"], float(meta.get("weight", 1.0)))
                    except (ValueError, KeyError, TypeError):
                        continue
            raw_rules = self.r.hgetall(KEY_CLS_RULES)
            if raw_rules:
                for cat, val in raw_rules.items():
                    try:
                        meta = json.loads(val)
                        rules[cat] = (int(meta["hit_count_threshold"]), float(meta["weight_sum_threshold"]))
                    except (ValueError, KeyError, TypeError):
                        continue
            raw_labels = self.r.hgetall(KEY_CLS_LABELS)
            if raw_labels:
                for cat, val in raw_labels.items():
                    labels[cat] = [s for s in val.split(",") if s]
            raw_primary = self.r.hgetall(KEY_CLS_PRIMARY)
            if raw_primary:
                for code, val in raw_primary.items():
                    try:
                        meta = json.loads(val)
                        if meta.get("status", "ENABLED") == "ENABLED":
                            category_names[code] = meta.get("name", code)
                            department_names[code] = meta.get("department", code)
                    except (ValueError, TypeError):
                        continue
        except Exception:
            # Redis 异常：返回空数据，AI 无法判定（need_review=true），不阻断
            pass
        self._cache = {
            "keywords": keywords, "rules": rules, "labels": labels,
            "categories": category_names, "departments": department_names,
        }
        # Redis 完全无数据时（Java 尚未同步分类知识桥）不缓存，下次立即重试，避免等满 60 秒
        has_data = bool(keywords or rules or labels or category_names)
        self._load_at = now if has_data else 0.0
        return self._cache

    def get_keywords(self) -> dict:
        """获取关键词字典（含分类和权重），带缓存"""
        return self._load()["keywords"]

    def get_rules(self) -> dict:
        """获取分类判定规则字典（命中数阈值与权重和阈值），带缓存"""
        return self._load()["rules"]

    def get_labels(self) -> dict:
        """获取一级分类对应的二级标签列表，带缓存"""
        return self._load()["labels"]

    def get_categories(self) -> dict:
        """获取一级分类代码 → 名称映射（从 cls:primary 读取）"""
        return self._load()["categories"]

    def get_departments(self) -> dict:
        """获取一级分类代码 → 分派部门映射（从 cls:primary 读取）"""
        return self._load()["departments"]

    def refresh(self):
        """强制刷新缓存：清空已缓存数据与加载时间戳，下次 _load() 立即重读 Redis。
        供管理端「分类效果在线测试」使用——刚改的关键词/规则期望立即生效，
        而常规缓存 60s 会延迟命中；真实分类流水线不调用，继续吃缓存降带宽。"""
        self._cache = {}
        self._load_at = 0.0
