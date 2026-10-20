# 05-deploy 部署项目目录结构

> 一键部署包：虚拟机 `~/05-deploy` 下 `docker compose up -d --build` 即完成全部服务部署。
> 更新时间：2026-09-05

```
05-deploy/
├── .env                          # compose 变量注入（跨容器凭据）：MySQL 密码 / MinIO 凭据与桶名 /
│                                 #   DASHSCOPE_API_KEY / AI 签名 / 阿里短信 AK·SK·签名名
├── docker-compose.yml            # 编排：mysql / redis / minio / minio-init / backend / ai / frontend
├── build.ps1                     # 本机构建辅助脚本（jar + dist 复制到对应目录）
├── README.md                     # 部署说明文档
│
├── backend/                      # 【后端容器】Spring Boot 3（02-backend 的部署形态）
│   ├── app.jar                   #   ★ 打包产物（02 mvn package 后复制进来；含全部功能）
│   └── Dockerfile                #   eclipse-temurin:17-jre + UTF-8 编码环境 + 暴露 8080
│
├── ai/                           # 【AI 容器】FastAPI + PydanticAI（03-fastAPI 的部署形态）
│   ├── .env                      #   AI 容器专属环境变量（env_file 注入）：
│   │                             #     LLM_MODEL=deepseek-v3.1 / EMBEDDING_MODEL / DASHSCOPE_BASE_URL /
│   │                             #     EMBEDDING_DIM / RAG_INDEX / CONFIDENCE_THRESHOLD 等
│   ├── requirements.txt          #   依赖清单：fastapi 0.141 / pydantic-ai 2.38 / openai 3.7 / redis 等
│   ├── requirements-lin.txt      #   旧版依赖清单（备用）
│   ├── Dockerfile                #   python:3.11-slim + 阿里 pip 源 + PYTHONUTF8=1 + 暴露 8000
│   └── app/                      #   AI 服务源码（与 03-fastAPI/app 同步）
│       ├── main.py               #     应用入口：生命周期（Redis 持续重连）/ 路由注册 / 内网签名
│       ├── config.py             #     配置读取（env：DASHSCOPE/REDIS/LLM_MODEL/阈值…）
│       ├── schemas.py            #     请求/响应 Pydantic 模型（各 AI 接口契约）
│       ├── routers/              #     路由层
│       │   ├── ai_router.py      #       全部 AI 端点（summarize/audit/classify/detect/dedup/…）
│       │   ├── auth.py           #       内网签名校验（verify_internal）
│       │   └── chat_router.py    #       智能问答（RAG 知识库对话）
│       ├── core/
│       │   ├── ai/               #     AI 能力实现层（PydanticAI Agent 化）
│       │   │   ├── agents.py     #       ★ PydanticAI 三个 Agent（标题/审核/分类，结构化输出）
│       │   │   ├── title_summarizer.py   # 标题生成（调 Agent）
│       │   │   ├── category_auditor.py   # 类别审核（调 Agent）
│       │   │   ├── classifier.py         # 智能分类（调 Agent，9 类候选）
│       │   │   ├── detector.py           # 诉求拆分 + 数据清洗（清洗+归一化+脱敏）
│       │   │   ├── deduper.py            # 去重归并相似度兜底（Embedding 余弦）
│       │   │   ├── reclassify.py         # 批量重分类（异步任务）
│       │   │   ├── llm_client.py         # OpenAI 兼容客户端（DashScope）/ Embedding
│       │   │   ├── predictor.py          # 诉求预判（趋势/热点）
│       │   │   ├── suggester.py          # 诉求填写智能补全
│       │   │   ├── sensitive_detector.py # 敏感内容检测
│       │   │   ├── warning_assistant.py  # 预警处置建议
│       │   │   ├── case_retriever.py     # 相似案例检索
│       │   │   ├── rag_retriever.py      # RAG 向量检索（Redis Stack HNSW）
│       │   │   └── chat_kb.py            # 知识库问答（RAG+RRF 融合）
│       │   └── util/             #     工具层
│       │       ├── rule_store.py         # 分类规则/标签/部门（Redis cls:* 读取）
│       │       ├── keyword_classifier.py # 关键词权重分类（备用策略）
│       │       └── preprocess.py         # 文本清洗/归一化/敏感词/PII 脱敏
│       └── routers/__init__.py 等
│
├── frontend/                     # 【前端容器】Nginx + 构建产物（01-frontend 的部署形态）
│   ├── dist/                     #   ★ 构建产物（01 npm run build 后复制进来；四端单页应用）
│   │   ├── index.html            #     入口（四端共用，路由分发 / /staff /admin）
│   │   ├── assets/               #     打包 JS/CSS（含 useNewMarker/NewDot/各页面 chunk）
│   │   └── screenshots/          #     各端截图（README 用）
│   ├── nginx.conf                #   Nginx 配置：80 端口 / utf-8 / 前端路由回退 /
│   │                             #     /api/v1/ 反代 backend:8080（SSE 关缓冲）/
│   │                             #     /minio/ 反代 minio:9000（附件同源访问）
│   └── Dockerfile                #   nginx:alpine
│
└── mysql/                        # 【数据库初始化】（MySQL 容器首次启动自动执行）
    └── init/
        ├── 00-init-all.sql       #   建库建表（30+ 表；utf8mb4；SET NAMES 头部）
        ├── 21-test-users.sql     #   测试账号种子（admin001/部门×8/街道办×7/白先生）
        ├── 23-test-appeals.sql   #   测试诉求种子（含归并组/各分类，海港区=东环路街道）
        ├── 25-seed-classify.sql  #   分类体系种子（一级/二级标签/关键词/规则/部门映射）
        ├── 26-seed-config.sql    #   配置种子（知识库/预判配置/预警规则）
        └── 27-seed-region.sql    #   区划种子（秦皇岛 7 区县 + 街道/镇/乡）
```

## 容器与端口

| 容器 | 端口（宿主机） | 说明 |
|---|---|---|
| hotline-mysql | 3306 | MySQL 8.0（utf8mb4），init 自动建库建表+种子 |
| hotline-redis | 6379 | Redis Stack（验证码/登录锁/Token 黑名单/RAG 向量） |
| hotline-minio | 9000 / 9001 | 对象存储（附件）/ 9001 控制台 |
| hotline-minio-init | — | 一次性：建桶 media-bucket + 设公开读 |
| hotline-backend | 8080 | Spring Boot（API + SSE） |
| hotline-ai | 8000 | FastAPI（PydanticAI：标题/审核/分类/拆分/预判…） |
| hotline-frontend | **80** | Nginx（四端页面 + /api 反代 + /minio 附件反代） |

## 数据持久化卷（docker compose down -v 会删除）

| 卷 | 内容 |
|---|---|
| mysql_data | 数据库全部数据（含 sys_module_read 游标——删除后红点计数归零重来） |
| redis_data | 验证码/Token 黑名单/RAG 向量 |
| minio_data | 已上传附件 |

## 局域网访问（宿主机 192.168.140.47 为例）

```
VMware VMnet8 NAT 端口转发：宿主机 80 → 192.168.114.100:80
宿主机防火墙放行 80
同事访问：http://192.168.140.47
附件预览：同源 /minio/ 反代（无需暴露 9000）
```
