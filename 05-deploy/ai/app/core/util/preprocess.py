"""文本预处理（需求 8.3(3)）：去除空白与特殊符号、统一全半角、简体化、jieba 分词、去除停用词"""
import re

import jieba
import zhconv

# 常用中文停用词（内置最小集，可按需扩展）
STOPWORDS = {
    "的", "了", "和", "是", "在", "我", "有", "就", "不", "人", "都", "一", "一个", "上", "也",
    "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这", "那",
    "他", "她", "它", "们", "被", "把", "给", "与", "及", "或", "等", "对", "从", "让",
    "请", "希望", "麻烦", "我们", "你们", "他们", "因为", "所以", "但是", "如果", "就是",
    "这个", "那个", "什么", "怎么", "为什么", "还有", "已经", "正在", "可以", "应该",
    "问题", "情况", "事情", "方面", "一下", "目前", "现在", "今天", "昨天", "最近", "时间",
}

# 全角 → 半角映射（统一全半角）
_FULLWIDTH = "０１２３４５６７８９ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ"
_HALFWIDTH = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
_FULL2HALF = str.maketrans(_FULLWIDTH, _HALFWIDTH)


def clean_text(text: str) -> str:
    """清洗：简体化 → 全角转半角 → 去除空白与特殊符号"""
    if not text:
        return ""
    text = zhconv.convert(text, "zh-cn")
    text = text.translate(_FULL2HALF)
    text = re.sub(r"\s+", "", text)
    text = re.sub(r"[^\u4e00-\u9fffa-zA-Z0-9，。！？；、：,.!?;:()（）\-]", "", text)
    return text


def segment(text: str) -> list:
    """jieba 分词并去除停用词"""
    return [w for w in jieba.cut(clean_text(text)) if w and w not in STOPWORDS]


def split_sentences(text: str) -> list:
    """按句末标点切分句子（多类别检测按句分析）"""
    parts = re.split(r"[。！？；!?;\n]+", clean_text(text))
    return [p.strip() for p in parts if len(p.strip()) >= 2]


# ------------------------------------------------------------------ 归一化
# 常见口语化表达 → 书面化（归一化）
_SPOKEN_REPLACE = {
    "不晓得": "不知道", "咋办": "怎么办", "为啥": "为什么",
    "啥": "什么", "咋": "怎么", "木有": "没有",
    "嘎嘎": "", "酱紫": "这样", "灰常": "非常",
}


def normalize_text(text: str) -> str:
    """归一化：口语化表达统一为书面语、规范标点、压缩重复语气词"""
    if not text:
        return ""
    for k, v in _SPOKEN_REPLACE.items():
        text = text.replace(k, v)
    # 连续重复标点压缩：。。→。 ！！→！
    text = re.sub(r"([。！？，、；：,.!?;:])\1+", r"\1", text)
    # 连续重复语气词压缩：啊啊→啊
    text = re.sub(r"([啊呀嘛呢吧啦哦噢哈]){2,}", r"\1", text)
    return text


# ------------------------------------------------------------------ 敏感词过滤
# 不当言论/攻击性敏感词（脱敏为 ***，政务热线场景）
_SENSITIVE_WORDS = {
    "傻逼", "妈的", "去死", "狗屁", "混蛋", "废物", "不要脸", "滚蛋", "畜生",
}


def filter_sensitive(text: str) -> str:
    """敏感词过滤：不当言论替换为 ***，并对手机号/身份证号做隐私脱敏"""
    if not text:
        return ""
    for w in _SENSITIVE_WORDS:
        if w in text:
            text = text.replace(w, "***")
    # 手机号脱敏：138****8001（前后非数字边界，避免误伤编号）
    text = re.sub(r"(?<!\d)1[3-9]\d{9}(?!\d)", lambda m: m.group()[:3] + "****" + m.group()[-4:], text)
    # 身份证号脱敏
    text = re.sub(r"(?<!\d)\d{17}[\dXx](?!\d)", "身份证号***", text)
    return text


# ------------------------------------------------------------------ 统一入口
def preprocess_with_log(text: str):
    """加工 + 留痕：清洗 → 归一化 → 敏感词过滤，返回 (加工后文本, 加工明细)。
    明细只记值得记的：敏感词替换、PII 脱敏；例行清洗（去空白/简体化/归一化）不记。"""
    if not text:
        return "", {}
    log = {"sensitive_replaced": [], "pii_masked": []}

    # 例行清洗 + 归一化（不记明细）
    text = clean_text(text)
    text = normalize_text(text)

    # 敏感词过滤（记明细）
    for w in _SENSITIVE_WORDS:
        if w in text:
            text = text.replace(w, "***")
            log["sensitive_replaced"].append(w)

    # PII 脱敏（记明细）
    def _mask_phone(m):
        masked = m.group()[:3] + "****" + m.group()[-4:]
        log["pii_masked"].append(masked)
        return masked

    def _mask_id(m):
        log["pii_masked"].append("身份证号***")
        return "身份证号***"

    text = re.sub(r"(?<!\d)1[3-9]\d{9}(?!\d)", _mask_phone, text)
    text = re.sub(r"(?<!\d)\d{17}[\dXx](?!\d)", _mask_id, text)

    if not log["sensitive_replaced"]:
        log.pop("sensitive_replaced")
    if not log["pii_masked"]:
        log.pop("pii_masked")
    return text, log


def preprocess(text: str) -> str:
    """统一预处理入口：清洗 → 归一化 → 敏感词过滤。
    外部调用方只需调用本函数即可完成全部加工；需要留痕时改用 preprocess_with_log。"""
    return preprocess_with_log(text)[0]
