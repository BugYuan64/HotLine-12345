# 12345政务热线工单智能分类与诉求预判系统

> 面向政务热线的全流程业务系统：市民提交诉求 → AI 智能分类/拆分/清洗 → 工单分派流转（管理端→部门端→街道办端）→ 市民评价 → 终审归档；叠加敏感事件感知、高频诉求预判、四端 AI 智能助手与全链路审计。
>
> 项目形态：**Vue3 多端单仓库前端 + Spring Boot 业务后端 + FastAPI/PydanticAI 智能服务 + MySQL/Redis Stack/MinIO 基础设施**，支持 Docker Compose 一键部署。

---

## 一、项目简介

本系统围绕 12345 政务热线"诉求受理 → 智能处理 → 工单处置 → 结果反馈"全链路设计，为**市民、管理端（热线中心）、部门端（各职能局）、街道办端**四类角色提供独立门户：

- **市民端**：在线提交诉求、附件上传、办理进度实时查询、满意度评价与重开申请；
- **管理端**：诉求 AI 自动处理（标题生成/分类/拆分）、工单分派与终审归档、预警预判、分类体系维护、统计报表、账号与审计管理；
- **部门端**：工单确认、分派街道办、处置审核、驳回流转；
- **街道办端**：工单处置、退回/延期/驳回、上传处置凭证。

AI 能力（分类、拆分、标题、审核、敏感检测、预判、RAG 问答）由独立的 Python 服务提供，Java 业务端通过内网接口调用，AI 不可用时自动降级、业务不中断。

---

## 二、系统架构

```
┌─────────────────────────────────────────────────────────────┐
│  前端（Nginx 单页应用，四端共用入口，路由分发）               │
│    / 市民端 · /admin 管理端 · /staff 部门端 · /street 街道办端 │
├────────────────────────┬────────────────────────────────────┤
│  Java 业务后端           │  Python AI / RAG 服务               │
│  Spring Boot 4 + MyBatis│  FastAPI + PydanticAI              │
│  /api/v1/public 市民端   │  /ai/v1/ 内网调用（IP白名单+签名）   │
│  /api/v1/admin  管理端   │  · 标题生成/分类/拆分/审核           │
│  /api/v1/staff   部门端   │  · 敏感检测/预判/预警建议            │
│  /api/v1/street 街道办端  │  · RAG 向量检索/四端知识库问答       │
│  /api/v1/common 通用（附件）                                   │
├────────────────────────┴────────────────────────────────────┤
│   MySQL 8.0（业务数据） · Redis Stack（缓存+向量检索）          │
│   MinIO（附件对象存储，/minio 同源反代）                       │
└─────────────────────────────────────────────────────────────┘
```

---

## 三、技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 前端 | Vue 3 + Element Plus + Vite + Pinia + Vue Router | 四端单仓库（01-frontend），共享组件与状态工厂 |
| 业务后端 | Java 17 + Spring Boot 4 + MyBatis + Lombok | 按角色分包（admin/staff/street/citizen/common） |
| AI 后端 | Python 3.11 + FastAPI + PydanticAI + OpenAI SDK + jieba | 独立 AI/RAG 服务（03-fastAPI） |
| 数据库 | MySQL 8.0（utf8mb4） | 30+ 业务表，四端同构副本表（工单/日志/通知） |
| 缓存/向量 | Redis Stack | 验证码、Token 黑名单、登录锁、分类知识桥（cls:*）、RAG 向量索引 |
| 对象存储 | MinIO | 附件/凭证/导出文件，限时预签名或同源反代访问 |
| 认证 | JWT（Bearer）+ AuthInterceptor 角色隔离 | 市民短信验证码 / 部门工号密码 / 管理员账号，bcrypt 加盐 |
| 部署 | Docker Compose（mysql/redis/minio/backend/ai/frontend） | 一键部署（05-deploy） |

---

## 四、功能详解

### 4.1 市民端（citizen-app）

| 功能 | 说明 |
|------|------|
| 注册/登录 | 手机号 + 短信验证码注册登录（支持滑块/图形验证码），邮箱/手机双通道找回密码；登录核验注册状态 |
| 首页 | 快捷入口（提交诉求/办理进度/通知公告）、最近 3 条诉求状态卡片、热门服务 |
| 提交诉求 | 诉求类别（8 大类+其他）、内容描述、区县/街道/社区/详细地址（支持全市范围宽泛诉求）、附件上传（≤10MB、图片/文档白名单） |
| AI 智能引导 | 填写过程中 AI 实时补全建议、缺失要素提醒、标题建议 |
| 敏感前置检测 | 提交时 AI 检测，命中六类敏感事件即剥离为"敏感事件"专人专线受理，不进入公开流程 |
| 办理进度查询 | 诉求编号 + 预留手机号双重核验（登录态免输手机号），任何状态可查 |
| 我的诉求 | 列表分页、状态筛选（受理中/处理中/可评价/已评价/已办结/已撤回）、诉求编号搜索；拆分后各子诉求独立展示状态（如"城管部门处理中"） |
| 诉求进度弹窗 | 顶部诉求信息（提交内容/地址/类别/附件，附件可预览下载）→ 诉求进度步骤条（已提交→管理端→部门端→街道办→已办结）→ 父/子诉求分组流转时间线 |
| 处置结果 | 街道办处置完成后懒加载查看：处置摘要、方案、部门、承办街道办、时间、处置凭证附件 |
| 满意度评价 | 星级 + 等级（非常满意~非常不满意）+ 文字说明，处置完成后 7 天内，按子诉求独立评价 |
| 申请重开 | 不满意/非常不满意可提交重开申请（≤500 字补充说明），每个工单最多 1 次，管理端审批 |
| 通知公告 | 公开通知列表/详情 |
| 我的消息 | 站内消息红点提醒，进入页面标记已读 |
| 个人中心 | 办理进度统计（受理中/处理中/可评价等）、退出登录 |
| AI 智能助手 | 四端独立知识库（RAG 流式问答），自动追问推荐 |

### 4.2 管理端（admin-app）

| 功能 | 说明 |
|------|------|
| 系统总览 | 核心指标卡片与图表（工单量、分类分布、处理时效等） |
| 父诉求管理 | 诉求查询/筛选/关键词检索；**自动拆分**（定时任务 AI 批量处理，仪表盘展示总进度）与**手动拆分**（勾选≤10 条）；拆分进度 SSE 实时推送；撤回拆分（可修改子诉求后回退）；查看/编辑/删除；诉求附件查看 |
| 子诉求管理 | 子诉求列表（AI 流水线各环节状态实时显示：标题生成→审核→纠正→分类→生成工单）；**自动建单**与**手动建单**（≤10 条）；查看工单（含诉求附件） |
| 工单管理 | 多条件查询；确认、升级、录入、编辑、删除（仅待分派可删）、批量导入；分类修正；自动派单/手动分派（按部门/街道办）；终审确认；归档；超期/关注度标记；关联子诉求与诉求附件展示；流转时间线 |
| 终审 | 终审通过/归档、终审退回（退回重新分派或部门）、查看市民评价与处置详情 |
| 分类规则维护 | 一级分类（8 大类+OTHER）与二级标签（40 个）CRUD；分类关键词与规则维护；RAG 知识库样本管理；规则/样本 Excel 导入导出；分类效果测试；分类准确率统计；知识桥（Redis cls:*）重建 |
| 预警与预判 | 预警规则配置（分类/部门/区县/周期/级别）、预警记录（AI 生成总结与应对措施）、**敏感事件页**（六类敏感事件列表、查看详情含全部诉求字段与附件、专人专线处理）、预判配置与预测结果 |
| 统计报表 | 诉求/工单多维统计、报表导出 |
| 通知管理 | 管理端通知发布（联动下架/编辑传播到部门端、街道办端、市民端副本） |
| 审计日志 | 系统审计、部门审计、街道办审计三类日志（append-only，按角色/模块/结果筛选）；登录日志 |
| 账号管理 | 用户 CRUD、角色与权限（RBAC）、账号注册**审批**（管理端/部门端账号须管理员审批后才能登录） |
| AI 智能助手 | 管理端专属知识库问答，可查询工单/诉求/预警等业务数据 |

### 4.3 部门端（staff-app）

| 功能 | 说明 |
|------|------|
| 工作台 | 待办统计、快捷入口 |
| 工单列表 | 仅展示在办/待办状态（待确认、待分派、街道办在办、待审核[含待市民评价/已评价/街道办已处理]、街道办驳回待处理），审核通过/驳回/归档后自动移出列表；未读"新"标记 |
| 工单查看 | 工单详情抽屉：诉求内容、市民信息（脱敏）、诉求附件（图片预览/文件下载）、处置凭证、市民评价（星级/等级/文字/时间） |
| 分派街道办 | 待确认/待分派工单下拉选择承办街道办（支持修改） |
| 处置审核 | 收到市民评价或超 7 天未评价后方可审核；审核通过/驳回 |
| 驳回流转 | 申请驳回（回管理端重新分派）、同意街道办驳回、街道办驳回后重新分派 |
| 部门审计 | 本部门操作审计日志（含收到工单、收到评价等） |
| 预警推送 | 部门相关预警记录查看 |
| 通知公告 / AI 智能助手 | 部门通知、部门知识库问答 |

### 4.4 街道办端（street-app）

| 功能 | 说明 |
|------|------|
| 工作台 | 待办统计、快捷入口 |
| 工单列表 | 仅显示"待街道办处理"工单，处理后/驳回/退回自动移出列表；未读"新"标记 |
| 工单处置 | 处置方案、处置结果、处置标签（已解决/部分解决/无法解决/转其他部门）、实际处置时间、处置人员；**处置凭证上传**（图片/文件，最多 5 个） |
| 退回/延期/驳回 | 退回申请、延期申请、直接驳回（驳回回部门端） |
| 工单详情 | 诉求内容、市民信息（脱敏）、诉求附件、处置凭证、部门审核意见、流转时间线 |
| 街道办审计 | 本街道办操作审计日志 |
| 预警推送 / 通知公告 / AI 智能助手 | 预警记录、通知、知识库问答 |

---

## 五、AI 智能能力（03-fastAPI）

| 能力 | 实现要点 |
|------|----------|
| 诉求标题生成 | PydanticAI Agent 对诉求内容总结标题（管理端收到诉求后自动生成） |
| 一级分类 | 8 大类 + OTHER 共 9 个候选（候选写死，不读库）；关键词权重 + LLM 语义 + RAG 检索混合，输出类别/置信度，LLM 不可用自动降级 |
| 二级标签 | 从 Redis `cls:labels` 读取对应一级分类的标签清单注入提示词约束，输出不在清单的强制归为"其他" |
| 诉求拆分 | 多诉求识别拆分为多个子诉求，各自独立生成标题/分类/工单 |
| 类别审核/纠正 | 内容与类别一致性审核，不一致进入纠正流程 |
| 数据清洗 | 文本清洗/归一化/PII 脱敏 |
| 去重归并 | 同源/重复诉求智能识别（Embedding 余弦相似度兜底） |
| 敏感事件检测 | 六大类 30+ 情形提示词，白名单校验，输出不在预定义类别的强制归为"none"（避免误报） |
| 诉求预判 | 高频诉求趋势/热点预测，输出预测结果与预判配置 |
| 预警总结与应对建议 | 命中预警规则后 AI 生成总结与处置建议，基于优秀案例向量检索（case_idx） |
| RAG 向量检索 | Redis Stack HNSW 索引（rag_idx），历史样本向量化，`FT.SEARCH` 相似度检索，待向量化队列后台处理 |
| 智能助手 | 四端独立知识库（citizen/staff/street/admin 索引隔离），RAG+RRF 融合流式问答，工具调用查询业务数据，每次回答后推荐追问 |
| 智能补全 | 诉求填写引导与缺失要素提醒 |

---

## 六、核心业务流程

### 6.1 诉求全生命周期

```
市民提交诉求（敏感前置检测）
  ├─ 命中敏感 → sensitive_event 剥离，专人专线受理（市民仅见进度）
  └─ 未命中 → 落库父诉求（PENDING_AI）
        → AI 标题生成（TITLE_PENDING → TITLE_GENERATING → TITLE_GENERATED）
        → AI 类别检测/拆分（SPLITTING → CHILDREN_GENERATED，生成子诉求）
        → 子诉求 AI 审核/纠正/一级分类/二级分类（AUDIT_PENDING → AUDITING → CORRECTING
          → CLASSIFYING → CLASSIFYING_SUB）
        → 生成工单（READY → ORDERING → ORDERED，work_order_appeal 关联）
```

### 6.2 工单流转状态机

```
PENDING_DISPATCH（待分派）
  → PENDING_DEPT_PROCESS（部门待确认）
  → PENDING_DEPT_ASSIGN（部门待分派街道办）
  → PENDING_STREET_PROCESS（街道办处理中）
  → PENDING_EVALUATE（待市民评价）
  → EVALUATED（已评价）→ DEPT_AUDITED（部门审核通过）→ CLOSED（办结）→ ARCHIVED（归档）

异常分支：
  部门申请驳回/管理端退回 → PENDING_REASSIGN / PENDING_DISPATCH
  街道办驳回 → STREET_REJECTED → 部门同意驳回 DEPT_REJECTED 或重新分派
  工单退回（退回申请） → PENDING_UPDATE → 街道办重新处理
  评价不满意 → 市民申请重开（最多 1 次，管理端审批）
```

- 部门/街道办使用四端同构副本表（admin_work_order / dept_work_order / street_work_order / admin_order_archive），流转时同步；
- 工作流使用**部门代码**（URBAN/TRAFFIC/…）作为主标识，中文名在展示层动态翻译；
- 工单与诉求记录使用软删除（is_deleted=1），业务查询统一过滤。

### 6.3 敏感事件处理

- 提交时 AI 前置检测，命中即剥离为敏感事件（sensitive_event 快照），**不生成父诉求/不拆分/不建单**；
- 敏感事件携带附件（attachment_ids），管理端预警页可查看全部字段与附件；
- 市民端"我的诉求"页顶部展示敏感事件区，提交后可直接查看处理进度。

### 6.4 未读与消息

- 三端（管理/部门/街道办）共享 `sys_module_read` 游标表，徽章数 = 游标后新增数据量（非累计），切换页面不重复计数；
- 新数据"红点"精确到行（newMarker 快照机制）；进入页面即标记已读且刷新不反弹；
- 市民端消息中心行级红点 + 独立徽章，进入页面全部置读。

---

## 七、项目目录结构

```
12345政务热线/
├── 01-frontend/               # Vue3 前端（四端单仓库）
│   ├── src/
│   │   ├── citizen-app/       #   市民端（views/router/api/stores）
│   │   ├── admin-app/         #   管理端
│   │   ├── staff-app/         #   部门端
│   │   ├── street-app/        #   街道办端
│   │   ├── shared/            #   共享组件（OrderTable/OrderDetailDialog/
│   │   │                      #     AttachmentGrid/AuditLogPanel/ChatFloatBall/useNewMarker…）
│   │   └── router/            #   主路由（四端前缀分发 + 守卫）
│   └── vite.config.js         # /api、/exports、/minio 代理
├── 02-backend/                # Spring Boot 业务后端（端口 8080）
│   └── src/main/java/org/tjetc/backend/
│       ├── controller/        #   admin/staff/street/citizen/common/agent
│       ├── service/           #   业务服务（按角色分包 + 审计/文件/预警/分类/日志）
│       ├── mapper/            #   MyBatis Mapper（接口 + resources/mapper/*.xml）
│       ├── entity/ dto/       #   实体与请求体
│       ├── security/          #   AuthInterceptor（JWT + 角色隔离）
│       └── config/            #   WebMvc/Redis/全局异常等
├── 03-fastAPI/                # Python AI/RAG 服务（端口 8000）
│   └── app/
│       ├── main.py            #   入口（Redis 持续重连 + 索引初始化）
│       ├── config.py schemas.py
│       ├── routers/           #   ai_router（分类/拆分/审核/敏感/预判） chat_router（问答）
│       └── core/ai/           #   agents.py（PydanticAI）/classifier/title_summarizer/
│                              #     detector/deduper/reclassify/sensitive_detector/
│                              #     predictor/warning_assistant/rag_retriever/case_retriever/chat_kb
├── 04-document/               # 文档与数据库脚本（见第八章）
├── 05-deploy/                 # Docker Compose 一键部署包（mysql/redis/minio/backend/ai/frontend）
└── doc_text/ 等               # 需求/设计/测试文档文本提取
```

---

## 八、数据库与初始化脚本（04-document）

| 脚本 | 说明 |
|------|------|
| 00-init-all.sql | 建库建表（30+ 表）：账号/角色/菜单、诉求父/子表、四端工单副本表、日志、评价、分类体系、预警、预测、敏感事件、消息、通知、附件、区划、阅读游标等 |
| 21-test-users.sql | 测试账号：管理端 admin001、8 大分类部门账号、56 部门账号（7 区县×8）、7 街道办、测试市民 |
| 23-test-appeals.sql | 测试诉求数据（含归并组、各分类） |
| 25-seed-classify.sql | 分类体系种子：8 大类 + 40 个二级标签 + 关键词 + 规则 + RAG 样本 |
| 26-seed-config.sql | 配置种子：知识库、预判配置、预警规则 |
| 27-seed-region.sql | 秦皇岛 7 区县 + 街道/镇/乡区划 |
| 28-seed-orders.sql | 历史工单种子（90 天 / 2856 条，供预判演示） |
| 29-time-adjust.sql / 24-clear-audit.sql | 时间校准 / 清理审计日志 |
| redis向量库重建.sh / redis重建语句.txt | Redis 向量索引与知识桥重建 |

> 注：`appeal_attachment` 与 `sys_file` 共同支撑附件存储（MinIO 对象 + 元数据表）。

---

## 九、快速启动与部署

### 9.1 开发环境

```bash
# 1. 数据库与 Redis：导入 04-document 下 SQL（00 → 21 → 23 → 25 → 26 → 27 → 28）
# 2. AI 服务（03-fastAPI）
cd 03-fastAPI
pip install -r requirements.txt
copy .env.example .env   # 配置 DashScope Key、Redis、签名密钥
uvicorn app.main:app --host 0.0.0.0 --port 8000

# 3. 后端（02-backend）
mvnw spring-boot:run     # 端口 8080，需 MySQL/Redis/MinIO/AI 就绪

# 4. 前端（01-frontend）
npm install && npm run dev   # 端口 5173，/api、/exports、/minio 已代理
```

### 9.2 Docker 一键部署（05-deploy）

```bash
# Windows 开发机：重新打包 jar + dist 并同步
powershell -ExecutionPolicy Bypass -File .\build.ps1
# 虚拟机：整个目录拷贝后
docker compose up -d --build
```

| 容器 | 端口 | 说明 |
|------|------|------|
| hotline-mysql | 3306 | MySQL 8.0，init 自动建库建表+种子 |
| hotline-redis | 6379 | Redis Stack（验证码/黑名单/RAG 向量） |
| hotline-minio | 9000/9001 | 附件对象存储 + 控制台 |
| hotline-backend | 8080 | Spring Boot（API + SSE） |
| hotline-ai | 8000 | FastAPI（PydanticAI） |
| hotline-frontend | 80 | Nginx（四端页面 + /api 与 /minio 反代） |

访问：`http://<主机IP>`（前端）→ `/admin`、`/staff`、`/street`、`/`（市民端）。

---

## 十、测试账号（种子数据）

| 端 | 账号 | 密码 | 说明 |
|----|------|------|------|
| 管理端 | admin001 | Admin@12345 | 系统管理员 |
| 部门端 | urban001 / traffic001 / market001 / environment001 / housing001 / labor001 / public001 / health001 | Admin@12345 | 8 大分类部门（另有 7 区县 × 8 分类共 56 个分区账号，如 hgurban001） |
| 街道办端 | street001 ~ street007 | Admin@12345 | 各街道办 |
| 市民端 | 15947339264 | 123456 | 测试市民（短信验证码在容器内以日志输出） |

---

## 十一、相关文档

| 文档 | 位置 |
|------|------|
| API 接口文档 | 04-document/12345政务热线工单智能分类与诉求预判系统-API接口文档.md |
| 需求分析 | 04-document/12345政务热线工单智能分类与诉求预判系统需求分析-最终.docx |
| 概要/详细/部署/测试设计说明书 | 24软件XXX项目名称组_张三_李四_王五_赵六/ |
| 部署说明 | 05-deploy/README.md、04-document/30-deploy-structure.md |

---

## 十二、关键设计约定

- **部门代码为主标识**：流程中只存代码（URBAN 等），中文名（城管部门）展示层翻译，代码一经定义不可变；
- **四端数据解耦**：工单/日志/通知均为 admin/dept/street 同构副本表，市民端独立落库 citizen_appeal；
- **审计日志 append-only**：sys_audit_log 禁止 UPDATE/DELETE，含归属端 target_type；
- **软删除**：诉求与工单使用 is_deleted=1，业务查询统一过滤；
- **AI 降级**：AI 服务不可用时分类/预判等接口返回降级标记，业务可人工兜底，不抛 500；
- **幂等 SQL**：所有初始化脚本含删除/存在性检查，可重复执行。
