"""Java 业务服务内网工具端点客户端（AI 助手工具调用回查业务数据）。

- 仅被 pydantic-ai 的 Agent tool 函数使用：携带用户上下文令牌（agent_context）回传，
  Java /internal/agent/* 校验内网签名（X-Timestamp/X-Internal-Sign）与令牌后，
  以该登录用户身份查询权限内数据（越权由 Java 各端 service 过滤）。
- 内网签名算法必须与 Java ChatProxyService/AgentContextService 一致：
  sign = HmacSHA256(INTERNAL_SIGN_SECRET, str(timestamp)).hexdigest()
"""
import hashlib
import hmac
import time
from typing import Any, Optional

import httpx
from loguru import logger

from app.config import INTERNAL_SIGN_SECRET, JAVA_BASE_URL


class JavaApiError(Exception):
    """Java 内网接口调用失败（非 200 / HTTP 异常），由 tool 向上抛给 agent 重试或据实回答"""


_client: Optional[httpx.AsyncClient] = None


def get_client() -> httpx.AsyncClient:
    global _client
    if _client is None:
        _client = httpx.AsyncClient(base_url=JAVA_BASE_URL, timeout=60.0)
    return _client


def _sign_headers(context_token: str) -> dict:
    ts = str(int(time.time()))
    sign = hmac.new(INTERNAL_SIGN_SECRET.encode("utf-8"), ts.encode("utf-8"), hashlib.sha256).hexdigest()
    headers = {"X-Timestamp": ts, "X-Internal-Sign": sign, "Content-Type": "application/json"}
    if context_token:
        headers["X-Agent-Context"] = context_token
    return headers


async def call_java(method: str, path: str, *,
                    params: Optional[dict] = None,
                    json_body: Optional[dict] = None,
                    context_token: str = "") -> Any:
    """调用 Java 内网工具端点并解包 Result 信封 {code,message,data}；
    code != 200 时抛 JavaApiError（message 为业务提示，供模型据实回答）。"""
    client = get_client()
    try:
        resp = await client.request(method, path, params=params, json=json_body,
                                    headers=_sign_headers(context_token))
    except httpx.HTTPError as e:
        logger.warning("Java 内网调用异常 {} {}: {}", method, path, e)
        raise JavaApiError(f"业务服务连接失败，请稍后再试") from e
    if resp.status_code != 200:
        raise JavaApiError(f"业务服务响应异常（HTTP {resp.status_code}）")
    try:
        body = resp.json()
    except ValueError as e:
        raise JavaApiError("业务服务响应解析失败") from e
    if isinstance(body, dict) and "code" in body:
        if body.get("code") != 200:
            raise JavaApiError(str(body.get("message") or "业务处理失败"))
        return body.get("data")
    return body


def close_client() -> None:
    """进程退出/测试收尾时释放连接（正常无需显式调用）。"""
    global _client
    if _client is not None:
        _client.aclose()
        _client = None
