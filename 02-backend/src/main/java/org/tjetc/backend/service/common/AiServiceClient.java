package org.tjetc.backend.service.common;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tjetc.backend.dto.ai.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Python AI 服务调用客户端（API 接口文档 1.2/十）：
 * - Java 后端通过 HTTP 调用 /ai/v1/，请求头携带内网签名 X-Internal-Sign: HMAC-SHA256(timestamp, secret)
 * - 大模型/向量检索异常时 AI 服务内部已降级；本客户端在网络异常/超时时返回空 Optional，
 *   由调用方按「AI 降级特殊响应」（code 200 + ai_degraded:true）处理，禁止抛 500
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiServiceClient {

    @Value("${app.ai.base-url}")
    private String baseUrl;

    @Value("${app.ai.sign-secret}")
    private String signSecret;

    @Value("${app.ai.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${app.ai.read-timeout-ms:30000}")
    private int readTimeoutMs;

    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ 调用基座

    /** AI 服务连通性探测（/ai/v1/health）：连接成功返回 true（带内网签名） */
    public boolean ping() {
        try {
            long timestamp = System.currentTimeMillis() / 1000;
            String sign = SecureUtil.hmacSha256(signSecret).digestHex(String.valueOf(timestamp));
            HttpResponse response = HttpRequest.get(baseUrl + "/ai/v1/health")
                    .header("X-Internal-Sign", sign)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .timeout(connectTimeoutMs)
                    .setConnectionTimeout(connectTimeoutMs)
                    .execute();
            return response.isOk();
        } catch (Exception e) {
            return false;
        }
    }

    private Optional<String> post(String path, Object body) {
        // 单次请求 + 快速失败降级：AI 慢/不可用时立即返回 empty 由调用方降级处理（不重试占线程，
        // 避免长事务持锁 Lock wait timeout、前端 30s 超时；连接失败不再抛异常）
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("AI 请求序列化失败 path={}: {}", path, e.getMessage());
            return Optional.empty();
        }
        try {
            long timestamp = System.currentTimeMillis() / 1000;
            String sign = SecureUtil.hmacSha256(signSecret).digestHex(String.valueOf(timestamp));
            try (HttpResponse response = HttpRequest.post(baseUrl + path)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Internal-Sign", sign)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .body(json)
                    .timeout(readTimeoutMs)
                    .setConnectionTimeout(connectTimeoutMs)
                    .execute()) {
                if (!response.isOk()) {
                    log.warn("AI 服务响应异常 path={} status={} body={}（降级）", path, response.getStatus(), response.body());
                    return Optional.empty();
                }
                return Optional.ofNullable(response.body());
            }
        } catch (Exception e) {
            log.warn("AI 服务连接失败 path={}：{}（降级）", path, e.getMessage());
            return Optional.empty();
        }
    }

    private <T> Optional<T> parse(Optional<String> body, Class<T> type) {
        // 解析数据
        if (body.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(body.get(), type));
        } catch (Exception e) {
            log.warn("AI 服务响应解析失败 type={}: {}", type.getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ 10.2 工单智能分类

    public Optional<AiClassifyResult> classify(Long orderId, String text) {
        // 分类工单（真实流水线：走 RuleStore 60s 缓存节流）
        return classify(orderId, text, false);
    }

    /** 分类工单（refresh=true 用于管理端「分类效果在线测试」：强制 Python 侧刷新知识桥 60s 缓存，
     *  保证刚修改的关键词/规则立即命中；真实流水线走无参重载保持缓存节流） */
    public Optional<AiClassifyResult> classify(Long orderId, String text, boolean refresh) {
        // 分类工单（order_id 可为空：Pydantic Optional[int] 不接受空字符串，须传 null）
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("order_id", orderId);
        body.put("text", text);
        if (refresh) {
            body.put("refresh", true);
        }
        return parse(post("/ai/v1/classify", body), AiClassifyResult.class);
    }

    // ------------------------------------------------------------------ 10.1 诉求多类别检测与拆分建议

    public Optional<AiDetectResult> detect(Long appealId, String text) {
        // 检测识别（appeal_id 可为空：Pydantic Optional[int] 不接受空字符串，须传 null）
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("appeal_id", appealId);
        body.put("text", text);
        return parse(post("/ai/v1/detect", body), AiDetectResult.class);
    }

    // ------------------------------------------------------------------ 10.4 智能诉求引导与补全

    public Optional<AiSuggestResult> suggest(String partialContent) {
        // 建议生成
        return parse(post("/ai/v1/suggest",
                java.util.Map.of("partial_content", partialContent)), AiSuggestResult.class);
    }

    // ------------------------------------------------------------------ 10.14 诉求标题 AI 总结

    public Optional<AiSummarizeResult> summarize(String text) {
        // 标题总结（市民提交诉求/拆分子诉求后按内容生成标题，<=25字）
        return parse(post("/ai/v1/summarize",
                java.util.Map.of("text", text)), AiSummarizeResult.class);
    }

    // ------------------------------------------------------------------ 10.15 诉求类别 AI 审核

    public Optional<AiAuditResult> auditCategory(String text, String category) {
        // 类别一致性审核（内容与类别是否一致；不一致由调用方进入 AI 分类纠正）
        return parse(post("/ai/v1/audit-category",
                java.util.Map.of("text", text, "category", category)), AiAuditResult.class);
    }

    // ------------------------------------------------------------------ 10.2.1 二级分类（一级已定，仅选二级标签）

    /**
     * 二级分类：一级分类已由审核/纠正/一级分类阶段确定，仅在 primary 对应的二级标签清单中选 sub_category。
     * 无匹配/降级时 Python 侧归为"其他"返回；AI 不可用返回 empty，调用方按"二级缺失不阻断流转"处理。
     */
    public Optional<AiSubClassifyResult> classifySecondary(String text, String primaryCategory) {
        // 二级分类（队列1 二级阶段：审核一致/纠正完成后/OTHER 一级分类完成后调用）
        return parse(post("/ai/v1/classify_secondary",
                java.util.Map.of("text", text, "primary_category", primaryCategory)),
                AiSubClassifyResult.class);
    }

    // ------------------------------------------------------------------ 10.6 高频诉求预判

    public Optional<AiPredictResult> predict(Integer historyDays, String predictPeriod,
                                             Double confidenceThreshold, Integer clusterCount) {
        // 预测分析
        var body = new java.util.LinkedHashMap<String, Object>();
        if (historyDays != null) {
            body.put("history_days", historyDays);
        }
        body.put("predict_period", predictPeriod);
        if (confidenceThreshold != null) {
            body.put("confidence_threshold", confidenceThreshold);
        }
        if (clusterCount != null) {
            body.put("cluster_count", clusterCount);
        }
        return parse(post("/ai/v1/predict", body), AiPredictResult.class);
    }

    // ------------------------------------------------------------------ 10.7 敏感/紧急事件实时感知

    public Optional<AiSensitiveResult> sensitive(Long appealId, String text) {
        // 执行sensitive操作（appeal_id 可为空：Pydantic Optional[int] 不接受空字符串，须传 null）
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("appeal_id", appealId);
        body.put("text", text);
        return parse(post("/ai/v1/sensitive", body), AiSensitiveResult.class);
    }

    // ------------------------------------------------------------------ 10.3 历史工单批量重分类

    public Optional<AiReclassifyResult> reclassify(List<Long> orderIds, boolean async) {
        // 执行reclassify操作
        var body = new java.util.LinkedHashMap<String, Object>();
        if (orderIds != null) {
            body.put("order_ids", orderIds);
        }
        body.put("async", async);
        return parse(post("/ai/v1/reclassify", body), AiReclassifyResult.class);
    }

    // ------------------------------------------------------------------ 10.9 预警总结生成

    public Optional<AiWarningSummaryResult> warningSummary(Long warningId, List<String> orderTexts) {
        // 预警Summary
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("warning_id", warningId);
        body.put("order_texts", orderTexts);
        return parse(post("/ai/v1/warning/summary", body), AiWarningSummaryResult.class);
    }

    // ------------------------------------------------------------------ 10.10 应对措施推荐

    public Optional<AiWarningMeasureResult> warningMeasure(String scene) {
        // 预警Measure
        return parse(post("/ai/v1/warning/measure",
                java.util.Map.of("scene", scene)), AiWarningMeasureResult.class);
    }

    // ------------------------------------------------------------------ 10.12 优秀处置案例检索（预警建议 RAG）

    public Optional<AiCaseSearchResult> searchCases(String query, String category, String region, Integer topK) {
        // 处置案例检索（warning_case 向量索引 case_idx；category/region 可选做过滤）
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("query", query);
        if (category != null && !category.isBlank()) {
            body.put("category", category);
        }
        if (region != null && !region.isBlank()) {
            body.put("region", region);
        }
        if (topK != null) {
            body.put("top_k", topK);
        }
        return parse(post("/ai/v1/warning/cases/search", body), AiCaseSearchResult.class);
    }

    // ------------------------------------------------------------------ 10.11 去重归并相似度判定

    public Optional<AiDedupResult> dedup(String text, double threshold, List<Map<String, Object>> candidates) {
        // 去重归并相似度兜底：返回语义最相似且达阈值的候选工单
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("text", text);
        body.put("threshold", threshold);
        body.put("candidates", candidates);
        return parse(post("/ai/v1/dedup", body), AiDedupResult.class);
    }
}