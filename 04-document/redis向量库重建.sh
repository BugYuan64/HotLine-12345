#!/bin/bash
# ============================================================
# Redis 向量库全量重建脚本（Linux）：清空 → 重建 → 重推 → 验证 一体化
#
# 适用场景：更换 Embedding 模型 / EMBEDDING_DIM 后，三套 RAG 向量库
#   （分类样本 rag_idx / 处置案例 case_idx / 四端客服知识 chat_*_idx）
#   的旧向量与新模型向量空间不兼容，必须全量重建。
#
# 执行内容：
#   [1] 删除 6 个 RediSearch 向量索引（FT.DROPINDEX DD，连文档一起删）
#   [2] 清理残留向量 key（rag:v:* / case:v:* / chat:*:v:*）
#   [3] 清空待向量化队列（rag:pending / case:pending，防旧 worker 用旧模型消费）
#   [4] 重建 AI 服务（自动识别 docker compose 项目并 --force-recreate，
#       重启时按新 EMBEDDING_DIM 重建索引 + 用新模型灌入四端内置 FAQ）
#   [5] 重推 MySQL 存量数据到队列（rag_sample / warning_case，仅 ENABLED，
#       JSON 字段与 Java pushRagPending / pushCasePending 完全一致，
#       由 AI 服务后台线程用新模型逐条 Embedding 入库）
#   [6] 等待队列消费完毕，输出 6 索引 num_docs 验证报告
#
# 使用方法（Linux）：
#   chmod +x redis向量库重建.sh
#   ./redis向量库重建.sh                    # 交互确认后执行
#   ./redis向量库重建.sh -y                 # 跳过确认
#   SKIP_MYSQL=1 ./redis向量库重建.sh -y    # 跳过 MySQL 重推（只重建客服 FAQ 库）
#
# 环境变量可覆盖（默认自动探测容器，兼容 05-deploy 与 03-fastAPI 两套命名）：
#   REDIS_CONTAINER  默认探测 hotline-redis / hotline-redis-stack
#   AI_CONTAINER     默认探测 hotline-ai / hotline-rag-service
#   MYSQL_CONTAINER  默认探测 hotline-mysql
#   MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB   默认 root / 123456 / hotline_12345
#   WAIT_TIMEOUT     队列消费等待超时秒数（默认 300）
#
# ⚠️ 前置条件（重要）：
#   1. 新模型配置已写入 ai 服务的环境文件（05-deploy/ai/.env 或 03-fastAPI/.env）：
#      EMBEDDING_MODEL / EMBEDDING_DIM / DASHSCOPE_API_KEY（本地模型密钥也要非空，
#      否则启动降级不灌库）
#   2. 本脚本对 compose 管理的容器用 --force-recreate 重建（重读 .env）；
#      docker restart 不会重读 .env，改过配置必须 recreate
#
# ⚠️ 原理（为什么必须删索引而不是等自动重建）：
#   - ensure_index() 只探测索引是否存在，不校验维度/向量空间
#   - 维度相同 → 旧向量与新查询向量空间不兼容，KNN 静默失效（最隐蔽的坑）
#   - 维度不同 → 写入/查询直接报错
#
# 不会动的数据：stats:daily:*、cls:*、predict:texts、token 等业务数据全部保留。
# ============================================================

set -euo pipefail

# ---------------- 参数解析 ----------------
ASSUME_YES=0
for a in "$@"; do
    case "$a" in
        -y|--yes) ASSUME_YES=1 ;;
        -h|--help) grep '^#' "$0" | head -n 40; exit 0 ;;
        *) echo "未知参数: $a（支持 -y 跳过确认）"; exit 1 ;;
    esac
done

# ---------------- 工具函数 ----------------
info() { echo "  $*"; }
ok()   { echo "  ✓ $*"; }
warn() { echo "  ⚠ $*" >&2; }

# redis-cli 快捷方式（经 docker exec）
r() { docker exec "$REDIS_CONTAINER" redis-cli "$@"; }

# mysql 快捷方式（容器内执行 SQL，隐藏密码告警）
mysqlq() {
    docker exec "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
        --default-character-set=utf8mb4 -N -B -e "$1" 2>/dev/null
}

# 容器自动探测：detect 容器变量名 候选名1 候选名2 ...
detect_container() {
    local var="$1"; shift
    if [ -n "${!var:-}" ]; then return 0; fi
    local c
    for c in "$@"; do
        if docker ps --format '{{.Names}}' | grep -qx "$c"; then
            printf -v "$var" '%s' "$c"
            return 0
        fi
    done
    return 1
}

# ---------------- 0. 容器探测与确认 ----------------
detect_container REDIS_CONTAINER hotline-redis hotline-redis-stack \
    || { echo "✗ 未找到 Redis 容器（hotline-redis / hotline-redis-stack），可用 REDIS_CONTAINER 指定"; exit 1; }
detect_container AI_CONTAINER hotline-ai hotline-rag-service \
    || { echo "✗ 未找到 AI 服务容器（hotline-ai / hotline-rag-service），可用 AI_CONTAINER 指定"; exit 1; }
detect_container MYSQL_CONTAINER hotline-mysql || MYSQL_CONTAINER=""

echo "========================================"
echo " Redis 向量库全量重建（清空 + 重建一体）"
echo " Redis 容器 : ${REDIS_CONTAINER}"
echo " AI 容器    : ${AI_CONTAINER}"
echo " MySQL 容器 : ${MYSQL_CONTAINER:-未探测到（将跳过存量重推）}"
echo "========================================"
echo "⚠️  将删除全部向量数据（不可恢复），并重建 AI 容器。"
echo "    请确认新模型配置（EMBEDDING_MODEL/EMBEDDING_DIM）已写入 .env！"
if [ "$ASSUME_YES" != "1" ]; then
    read -r -p "确认继续？[y/N] " ans
    case "$ans" in y|Y|yes|YES) ;; *) echo "已取消"; exit 1 ;; esac
fi

# ---------------- 1. 删除 6 个向量索引（DD 连文档一起删） ----------------
echo ""
echo "[1] 删除向量索引（FT.DROPINDEX DD）..."
for idx in rag_idx case_idx chat_citizen_idx chat_staff_idx chat_street_idx chat_admin_idx; do
    out=$(r FT.DROPINDEX "$idx" DD 2>/dev/null || true)
    case "$out" in
        OK) ok "${idx} 已删除" ;;
        *)  info "${idx} 不存在（跳过）" ;;
    esac
done

# ---------------- 2. 清理残留向量 key ----------------
echo ""
echo "[2] 清理残留向量 key..."
for pat in 'rag:v:*' 'case:v:*' 'chat:*:v:*'; do
    cnt=$(docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern "$pat" 2>/dev/null | wc -l)
    docker exec "$REDIS_CONTAINER" sh -c "redis-cli --scan --pattern '$pat' | xargs -r redis-cli DEL" >/dev/null 2>&1
    ok "${pat} 已清理 ${cnt} 个"
done

# ---------------- 3. 清空待向量化队列 ----------------
echo ""
echo "[3] 清空待向量化队列（防旧 worker 用旧模型消费）..."
for q in rag:pending case:pending; do
    len=$(r LLEN "$q" 2>/dev/null || echo 0)
    r DEL "$q" >/dev/null
    ok "${q} 已清空 ${len} 条"
done

# ---------------- 4. 重建 AI 服务（重读 .env → 重建索引 + 灌 FAQ） ----------------
echo ""
echo "[4] 重建 AI 服务..."
COMPOSE_DIR=$(docker inspect "$AI_CONTAINER" --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' 2>/dev/null || true)
COMPOSE_SVC=$(docker inspect "$AI_CONTAINER" --format '{{index .Config.Labels "com.docker.compose.service"}}' 2>/dev/null || true)
if [ -n "$COMPOSE_DIR" ] && [ -n "$COMPOSE_SVC" ] && [ -d "$COMPOSE_DIR" ]; then
    ok "识别到 compose 项目：${COMPOSE_DIR}（服务 ${COMPOSE_SVC}）→ --force-recreate 重建（重读 .env）"
    (cd "$COMPOSE_DIR" && docker compose up -d --force-recreate --no-deps "$COMPOSE_SVC")
else
    warn "AI 容器非 compose 管理，回退 docker restart（不会重读 .env！）"
    warn "若修改过模型环境变量，请手动执行：docker compose up -d --force-recreate <ai服务名>"
    docker restart "$AI_CONTAINER"
fi

# 等待启动完成：chat_citizen_idx 出现即代表 lifespan 索引重建已执行
echo "[4.2] 等待 AI 服务重建索引..."
waited=0
until r FT.INFO chat_citizen_idx 2>/dev/null | grep -q 'num_docs'; do
    sleep 3; waited=$((waited + 3))
    if [ "$waited" -ge 180 ]; then
        warn "等待索引超时（180s），请排查：docker logs ${AI_CONTAINER} --tail 50"
        exit 1
    fi
done
ok "索引已按新配置重建（耗时 ${waited}s），内置 FAQ 灌库进行中"

# ---------------- 5. 重推 MySQL 存量数据到队列 ----------------
echo ""
echo "[5] 重推 MySQL 存量数据（JSON 格式与 Java 推送一致）..."
if [ "${SKIP_MYSQL:-0}" = "1" ]; then
    warn "SKIP_MYSQL=1，跳过存量重推（仅完成客服 FAQ 库重建）"
elif [ -z "$MYSQL_CONTAINER" ]; then
    warn "未找到 MySQL 容器，跳过存量重推（可用 MYSQL_CONTAINER 指定）"
else
    # 分类样本 → rag:pending：{"sample_id","category","content"}
    #   category 取 classify_category.primary_category（与 ClassifyService.pushRagPending 一致）
    RAG_SQL="SELECT JSON_OBJECT('sample_id', s.id, 'category', c.primary_category, 'content', s.content)
             FROM rag_sample s JOIN classify_category c ON s.category_id = c.id
             WHERE s.status = 'ENABLED'"
    # 处置案例 → case:pending：{"case_id","case_no","title","category","region",
    #   "content","measure_plan","measure_result","search_text"}
    #   search_text = title + '。' + content（与 WarningCaseService.pushCasePending 一致）
    CASE_SQL="SELECT JSON_OBJECT('case_id', wc.id, 'case_no', wc.case_no, 'title', wc.title,
                'category', wc.category, 'region', wc.region, 'content', wc.content,
                'measure_plan', wc.measure_plan, 'measure_result', wc.measure_result,
                'search_text', CONCAT(wc.title, '。', wc.content))
              FROM warning_case wc WHERE wc.status = 'ENABLED'"

    push_mysql_queue() {  # $1=队列名 $2=SQL；逐行 LPUSH，返回推送条数
        local queue="$1" sql="$2" n=0 line
        while IFS= read -r line; do
            line=${line%$'\r'}
            [ -z "$line" ] && continue
            r LPUSH "$queue" "$line" >/dev/null
            n=$((n + 1))
        done < <(mysqlq "$sql")
        echo "$n"
    }

    n1=$(push_mysql_queue rag:pending "$RAG_SQL")
    ok "rag_sample → rag:pending 已推 ${n1} 条"
    n2=$(push_mysql_queue case:pending "$CASE_SQL")
    ok "warning_case → case:pending 已推 ${n2} 条"
    if [ "$n1" = "0" ] && [ "$n2" = "0" ]; then
        info "（两表均无 ENABLED 数据属正常——演示库可能未维护样本/案例）"
    fi
fi

# ---------------- 6. 等待队列消费完毕（新模型逐条 Embedding） ----------------
echo ""
echo "[6] 等待队列消费（每条调用一次 Embedding，条数多时耗时较长）..."
waited=0
while :; do
    l1=$(r LLEN rag:pending 2>/dev/null || echo 0)
    l2=$(r LLEN case:pending 2>/dev/null || echo 0)
    [ "$l1" = "0" ] && [ "$l2" = "0" ] && break
    sleep 5; waited=$((waited + 5))
    if [ "$waited" -ge "$WAIT_TIMEOUT" ]; then
        warn "等待超时（${WAIT_TIMEOUT}s）：rag:pending 余 ${l1} 条，case:pending 余 ${l2} 条"
        warn "模型不可用时 worker 会把条目推回队列重试（30s 间隔），请检查："
        warn "  docker logs ${AI_CONTAINER} --tail 50   # 看 Embedding 调用失败日志"
        warn "  模型恢复后无需重跑本脚本，队列会自动消费完毕"
        break
    fi
done
[ "$l1" = "0" ] && [ "$l2" = "0" ] 2>/dev/null && ok "队列消费完毕（耗时 ${waited}s）" || true

# ---------------- 7. 验证报告 ----------------
echo ""
echo "========================================"
echo " ✓ 重建完成 — 各索引文档数"
echo "========================================"
for idx in rag_idx case_idx chat_citizen_idx chat_staff_idx chat_street_idx chat_admin_idx; do
    docs=$(r FT.INFO "$idx" 2>/dev/null | awk '/^num_docs$/{getline; print; exit}')
    printf "  %-18s %s\n" "$idx" "${docs:-0}"
done
echo ""
echo "后续建议："
echo "  1. 市民端问一个知识库问题，验证「知识来源」引用命中正常"
echo "  2. 管理端预警页生成一次处置建议，验证 case_idx 检索正常"
echo "  3. 管理员上传过的客服文档需重新上传（上传文档无 MySQL 源，仅存向量库）"
echo ""
