"""内网签名校验（API 接口文档 十、安全方案）

- 请求头携带内网签名 X-Internal-Sign: HMAC-SHA256(timestamp, secret)
- 校验时间戳窗口与签名，可选 IP 白名单（生产环境由 Nginx 限制仅 Java 服务 IP 可访问）
"""
import hashlib
import hmac
import time

from fastapi import Header, HTTPException, Request

from app.config import ALLOWED_IPS, INTERNAL_SIGN_SECRET, SIGN_TOLERANCE_SECONDS

SIGN_HEADER = "X-Internal-Sign"
TS_HEADER = "X-Timestamp"


def verify_internal(
    request: Request,
    x_internal_sign: str = Header(default="", alias=SIGN_HEADER),
    x_timestamp: str = Header(default="", alias=TS_HEADER),
) -> None:
    """内网签名校验：验证IP白名单、时间戳有效性和HMAC-SHA256签名，防止未授权访问"""
    if ALLOWED_IPS and request.client and request.client.host not in ALLOWED_IPS:
        raise HTTPException(status_code=403, detail="IP not allowed")
    if not x_timestamp or not x_internal_sign:
        raise HTTPException(status_code=401, detail="missing sign headers")
    try:
        ts = int(x_timestamp)
    except ValueError:
        raise HTTPException(status_code=401, detail="bad timestamp")
    if abs(time.time() - ts) > SIGN_TOLERANCE_SECONDS:
        raise HTTPException(status_code=401, detail="timestamp expired")
    expected = hmac.new(INTERNAL_SIGN_SECRET.encode(), x_timestamp.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, x_internal_sign):
        raise HTTPException(status_code=401, detail="bad sign")
