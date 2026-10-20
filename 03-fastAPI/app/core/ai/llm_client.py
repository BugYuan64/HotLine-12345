"""大模型与 Embedding 客户端（openai-sdk → 阿里云 DashScope OpenAI 兼容模式）

技术选型（用户确认）：大模型 qwen-plus / 向量化 text-embedding-v3（阿里云）
未配置 DASHSCOPE_API_KEY 时抛出 LlmUnavailable，上层按文档降级策略处理。
"""
import json
import re
from typing import Any

from loguru import logger

from app.config import DASHSCOPE_API_KEY, DASHSCOPE_BASE_URL, EMBEDDING_MODEL, LLM_MODEL


class LlmUnavailable(Exception):
    """大模型/向量化服务不可用（分类时自动降级为关键词规则，服务恢复后自动切回）"""


_client = None


def get_client() -> Any:
    """获取或初始化OpenAI兼容客户端（阿里云DashScope），未配置密钥时抛LlmUnavailable"""
    global _client
    if not DASHSCOPE_API_KEY:
        raise LlmUnavailable("DASHSCOPE_API_KEY 未配置，大模型能力不可用（自动降级）")
    if _client is None:
        from openai import OpenAI

        _client = OpenAI(api_key=DASHSCOPE_API_KEY, base_url=DASHSCOPE_BASE_URL)
    return _client


def _strip_markdown_fence(content: str) -> str:
    """剥离 LLM 偶发输出的 Markdown 代码块围栏（```json ... ```），保证 json.loads 可解析"""
    content = content.strip()
    if content.startswith("```"):
        content = re.sub(r"^```(?:json)?\s*", "", content)
        content = re.sub(r"\s*```$", "", content)
    return content.strip()


def chat_json(system: str, user: str, timeout: float | None = 14,
              model: str | None = None, disable_thinking: bool = True) -> dict:
    """大模型对话并解析 JSON 输出（分类/拆分/建议/敏感感知等结构化任务）；
    timeout：本请求超时秒数（默认 14s，略低于 Java 侧 15s 读超时：本端先失败返回明确降级，
    而非 Java 干等 15s 断连后 Python 仍挂着占用线程）；
    model：本次调用使用的模型（None 用默认 LLM_MODEL）；
    disable_thinking：qwen3 系列关闭思考模式，默认开启关闭（非流式调用先思考再输出动辄 40s+，
    曾导致市民端诉求建议等交互链路全部超时降级；确需深度思考的高风险任务才显式传 False）"""
    try:
        client = get_client()
        kwargs: dict = {
            "model": model or LLM_MODEL,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": 0.1,
            "response_format": {"type": "json_object"},
            "timeout": timeout,
        }
        # enable_thinking 仅 qwen3 系列支持；其余模型（如 deepseek）不传，避免参数校验报错
        if disable_thinking and "qwen3" in str(kwargs["model"]):
            kwargs["extra_body"] = {"enable_thinking": False}
        resp = client.chat.completions.create(**kwargs)
        content = _strip_markdown_fence(resp.choices[0].message.content or "")
        return json.loads(content)
    except LlmUnavailable:
        raise
    except Exception as e:
        logger.warning("大模型调用失败，自动降级：{}", e)
        raise LlmUnavailable(f"大模型调用失败: {e}") from e


def embed(texts: list) -> list:
    """文本向量化（阿里云 text-embedding-v3）"""
    if not texts:
        return []
    try:
        client = get_client()
        resp = client.embeddings.create(model=EMBEDDING_MODEL, input=texts)
        return [item.embedding for item in resp.data]
    except LlmUnavailable:
        raise
    except Exception as e:
        logger.warning("Embedding 调用失败：{}", e)
        raise LlmUnavailable(f"Embedding 调用失败: {e}") from e


def model_name() -> str:
    """返回当前使用的模型名称，未配置密钥时返回降级模式标识"""
    return LLM_MODEL if DASHSCOPE_API_KEY else "keyword-rule(fallback)"


def chat_stream(messages: list, temperature: float = 0.3):
    """大模型流式对话生成器（智能助手 SSE 问答）：
    逐 chunk 产出文本增量；未配置密钥/调用失败时抛 LlmUnavailable，由上层降级。
    """
    try:
        client = get_client()
        stream = client.chat.completions.create(
            model=LLM_MODEL,
            messages=messages,
            temperature=temperature,
            stream=True,
        )
        for chunk in stream:
            if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
                yield chunk.choices[0].delta.content
    except LlmUnavailable:
        raise
    except Exception as e:
        logger.warning("大模型流式调用失败：{}", e)
        raise LlmUnavailable(f"大模型流式调用失败: {e}") from e
