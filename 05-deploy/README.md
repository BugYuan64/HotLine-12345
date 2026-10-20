# 05-deploy — 12345 政务热线一键部署（任意 IP 的虚拟机/服务器均可）

## 内容
- docker-compose.yml：mysql + redis(stack) + backend(jar) + ai(fastapi) + frontend(nginx)
- mysql/init：建库建表与种子数据（00-init、21 账号、23 诉求、25 分类、26 配置、27 区划、28 历史工单90天/2856条供预判演示）
- backend/app.jar：02-backend 打包产物（build.ps1 生成）
- frontend/dist：01-frontend 打包产物（build.ps1 生成）
- ai/：03-fastAPI 源码（app/ + requirements.txt）

## 打包（Windows 开发机）
```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```
（重新构建 01/02 并把产物、03 AI 源码、04 数据库脚本同步进本目录；SQL 自动从 04-document 拉最新版）

## 部署（虚拟机，IP 可随意更换）
```bash
# 1. 把 05-deploy 整个目录拷到虚拟机（如 /root/05-deploy）
# 2. 按需修改 .env（DashScope 密钥等）
# 3. 启动（首次会自动建库建表灌数据；backend/ai 需在线构建）
docker compose up -d --build
# 4. 查看
docker compose ps
docker compose logs -f backend
```
> 部署不依赖任何固定 IP：容器之间全部用 compose 服务名通信，前端用同源相对路径，
> 宿主机/虚拟机 IP 怎么变都能正常部署与使用，只需把「访问」地址换成当前机器 IP。

## 访问
- 前端（四端入口）：http://<虚拟机当前IP>（示例 http://192.168.114.100）
- 后端 API：http://<虚拟机当前IP>/api/v1/**（nginx 反代）或直连 8080
- AI 服务：8000（内网签名）
- MinIO：compose 内 minio/minio 容器（数据存 minio_data 卷，minio-init 自动建桶）

## 配置注入（不改 01/02/03 源码）
backend/ai 通过环境变量覆盖（全部使用 compose 服务名，与宿主机/虚拟机 IP 无关）：
- backend：SPRING_DATASOURCE_URL（mysql 服务名）、SPRING_DATA_REDIS_HOST（redis）、
  APP_AI_BASE_URL（ai 服务名）、APP_MINIO_ENDPOINT（minio 服务名）、APP_SMS_SENDER（容器内验证码看日志）
- ai：REDIS_HOST（redis）、JAVA_BASE_URL（backend 服务名，AI 助手工具回调查业务数据；缺省会导致四端 AI 助手"查工单/进度"等功能不可用）
登录后首页默认账号见 mysql/init/21-test-users.sql（admin001 / Admin@12345；市民白先生 15947339264 / 123456）。

## IP 与地址规划（重要）
### 前置：关闭虚拟机上已运行的 MySQL、Redis 与 MinIO
compose 的 mysql/redis/minio 需要绑定 3306/6379/9000/9001，与已运行的旧服务端口冲突（此前 backend 未启动的原因）。
启动本部署前先停掉旧服务（含旧的 MinIO 容器，数据由 compose 卷接管）：

    systemctl stop mysqld redis   # 或 docker stop 旧的 mysql/redis/minio 容器

MinIO 已纳入 compose（minio/minio 镜像 + minio-init 一次性任务自动创建 media-bucket 桶），数据存 minio_data 卷。

### 地址口径（三类）
| 访问方向 | 地址 | 说明 |
|---|---|---|
| 浏览器访问 | http://<虚拟机当前IP>（示例 192.168.114.100） | 前端 nginx；API 同源 /api/v1 由 nginx 反代，无跨域 |
| 容器之间 | 服务名：mysql:3306、redis:6379、ai:8000、backend:8080 | compose 内部网络用服务名，不要写 IP（容器重建 IP 会变） |
| 容器 → MinIO | http://minio:9000（compose 服务名） | minio-init 自动建 media-bucket |

### 宿主机 / 虚拟机 IP 变化后怎么办（无需改任何配置）
- 部署与运行完全不依赖固定 IP：容器间用服务名、前端用同源相对路径、附件用 /minio/ 同源反代，
  即使宿主机局域网 IP 或虚拟机 IP 都变了，`docker compose up -d` 依然可用，无需修改任何文件。
- 虚拟机网络为 VMware NAT（VMnet8）时：虚拟机网段（如 192.168.114.x）是独立虚拟网段，
  **宿主机换 WiFi/换网络导致宿主机 IP 变化，完全不影响虚拟机 IP**——宿主机浏览器照常访问 http://<虚拟机IP>。
- 虚拟机网络为桥接（Bridged）时：虚拟机 IP 随局域网 DHCP 变化，在虚拟机内 `ip addr` 查看新 IP 后用新 IP 访问即可，部署本身零改动。
- 若用 VMnet8 NAT 端口转发（宿主机 80 → 虚拟机 80），换宿主机 IP 后同事访问 http://<宿主机新IP> 即可。

### 各端配置速查
| 端 | 连接什么 | 地址 |
|---|---|---|
| backend（Java） | MySQL | jdbc:mysql://mysql:3306/hotline_12345 |
| backend（Java） | Redis | redis:6379 |
| backend（Java） | AI 服务 | http://ai:8000 |
| backend（Java） | MinIO | http://minio:9000 |
| ai（Python） | Redis | redis:6379 |
| ai（Python） | backend（AI 助手工具回调） | http://backend:8080（JAVA_BASE_URL） |
| 前端（浏览器） | 后端 API | 同源 /api/v1 → nginx 反代 backend:8080 |

### 常见问题
backend 一直不启动 / 登录报网络异常：99% 是 mysql 或 redis 容器没起来（端口被旧服务占用）。用 docker compose ps -a 看状态、
docker compose logs redis 看原因；停掉占用 3306/6379 的旧服务后重新 docker compose up -d。

## 中文乱码保障（已内置四层，无需额外操作）
1. mysql/init/*.sql：全部 UTF-8 无 BOM，且每个文件首行 `SET NAMES utf8mb4`（客户端字符集强制声明）
2. mysql 容器启动参数：`--character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci`
3. backend 容器：JDBC URL 带 `characterEncoding=UTF-8`，镜像 LANG=C.UTF-8 + `-Dfile.encoding=UTF-8`
4. frontend 容器：nginx `charset utf-8`；ai 容器：PYTHONUTF8=1

拷贝到虚拟机时保持二进制拷贝（scp/共享文件夹/压缩包均可），不要用 FTP 文本模式（会转码破坏 UTF-8）。

## 部署后自动初始化（无需手工操作）
首次 `docker compose up -d --build` 后，库/向量库/知识桥按以下链路自动就绪：

| 步骤 | 执行者 | 内容 |
|---|---|---|
| 1. 建库建表灌种子 | mysql 容器 init 脚本 | hotline_12345 库 + 账号/分类/配置/区划/90天工单（00/21/23/25/26/27/28） |
| 2. 建桶 | minio-init 一次性任务 | MinIO media-bucket（公开读，附件直链） |
| 3. 建向量索引 | ai 启动（main.py） | FT.CREATE：rag_idx / case_idx / chat_{citizen,staff,street,admin}_idx |
| 4. 四端智能助手知识库 | ai 启动 | 索引为空时自动灌入各端内置 FAQ（需 DASHSCOPE_API_KEY） |
| 5. 分类知识桥 | backend 启动（ClassificationStartupRunner） | 全量重建 cls:keywords/cls:rules/cls:labels/cls:primary |
| 6. 分类样本向量化 | backend 启动 → rag:pending → ai 后台线程消费 | rag_sample 表 16 条种子样本推送 Embedding 入 rag_idx（向量库缺数才补推，幂等） |
| 7. 预判统计桥 | backend 启动（PredictionStartupRunner）/ 定时 | 工单聚合写 stats:daily:* + predict:texts，供 AI 预测热点 |

> 验证：`docker compose logs ai | grep 向量`（索引创建/灌库）、`docker compose logs backend | grep RAG`（播种完成）。
> 未填 DASHSCOPE_API_KEY 时样本暂存 rag:pending 队列，补填密钥后重启 ai 容器自动消费。

## 数据更新
- 改了 04-document 的 SQL：重跑 build.ps1（自动同步），虚拟机上 `docker compose down -v && docker compose up -d --build`
  （-v 清数据卷重建库；注意会清空运行期数据，导出 Excel/上传文件在 backend_exports/backend_uploads 卷中）
- 只改了 AI 知识库/推荐问题：重跑 build.ps1 后，虚拟机上 `docker compose up -d --build ai` 即可
