# AI 服务镜像：python3.11-slim + fastapi（requirements.txt 与 app/ 由 build.ps1 从 03-fastAPI 复制）
FROM python:3.11-slim
WORKDIR /app
ENV TZ=Asia/Shanghai PYTHONUNBUFFERED=1 PYTHONUTF8=1
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir --timeout 300 --retries 10 -r /app/requirements.txt -i https://mirrors.aliyun.com/pypi/simple/
COPY app /app/app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
