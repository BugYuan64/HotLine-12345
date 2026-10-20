package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.admin.PredictionConfigRequest;
import org.tjetc.backend.dto.ai.AiHotspot;
import org.tjetc.backend.dto.ai.AiPredictResult;
import org.tjetc.backend.dto.ai.AiTrendPoint;
import org.tjetc.backend.entity.admin.PredictionConfig;
import org.tjetc.backend.entity.admin.PredictionResult;
import org.tjetc.backend.entity.admin.PredictionTrend;
import org.tjetc.backend.mapper.admin.PredictionConfigMapper;
import org.tjetc.backend.mapper.admin.PredictionResultMapper;
import org.tjetc.backend.mapper.admin.PredictionTrendMapper;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;
import org.tjetc.backend.service.common.AiServiceClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 诉求预判分析（需求 9 诉求预判 / API 8.3/8.8/8.9/10.6/10.7）：
 * - 预判数据准备：基于全平台历史工单数据（诉求时间/分类/辖区）构建统计桥写入 Redis，
 *   由 Python AI 服务读取执行时序预测（Python 不访问业务表，架构隔离）
 * - 预判参数配置调整后即时生效
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

    private static final Map<String, String> PERIOD_MAP = Map.of(
            "DAY", "DAY", "3DAY", "3_DAY", "7DAY", "7_DAY");

    /** 周期天数映射（P0-2 回填写周期窗口用） */
    private static final Map<String, Integer> PERIOD_DAYS = Map.of("DAY", 1, "3_DAY", 3, "7_DAY", 7);

    @Value("${app.prediction.cron:0 17 1 * * ?}")
    private String cron;

    private final PredictionResultMapper resultMapper;
    private final PredictionConfigMapper configMapper;
    private final PredictionTrendMapper trendMapper;
    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final AiServiceClient aiServiceClient;
    private final AuditLogService auditLogService;
    private final WarningService warningService;
    private final StringRedisTemplate redis;

    // ------------------------------------------------------------ 预判数据准备

    /**
     * 聚合子诉求事件数据写入 Redis 统计桥（Python AI 服务读取）：
     * stats:daily:{yyyyMMdd} HASH {category}:{sub_category}:{region} -> 当日子诉求事件数
     * predict:texts HASH {category}:{sub_category}:{region} -> 近期子诉求文本（||分隔，热点主题聚类用）
     * region 已归一化到区县级，sub_category 为空时以空串占位，保证三元组键稳定可解析
     */
    public void refreshRedisStats(int historyDays) {
        // 刷新RedisStats
        try {
            // 先删除旧统计桥再写入：put 只覆盖同名 field，已删除/变更的工单会残留脏数据
            // （如重导种子 SQL 后 Redis 中旧 field 仍在，导致预判读到 DB 已不存在的序列）
            List<String> staleKeys = new ArrayList<>();
            LocalDate today = LocalDate.now();
            for (int i = 0; i <= historyDays; i++) {
                staleKeys.add("stats:daily:" + today.minusDays(i).format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            }
            staleKeys.add("predict:texts");
            redis.delete(staleKeys);
            List<Map<String, Object>> daily = adminWorkOrderMapper.aggregateDailyByCategoryRegion(historyDays);
            for (Map<String, Object> row : daily) {
                String date = String.valueOf(row.get("d"));
                String field = predictionKey(row.get("category"), row.get("sub_category"), row.get("region_norm"));
                redis.opsForHash().put("stats:daily:" + date, field, String.valueOf(row.get("cnt")));
            }
            List<Map<String, Object>> texts = adminWorkOrderMapper.selectRecentTexts(historyDays);
            Map<String, StringBuilder> buckets = new LinkedHashMap<>();
            for (Map<String, Object> row : texts) {
                String key = predictionKey(row.get("category"), row.get("sub_category"), row.get("region_norm"));
                StringBuilder sb = buckets.computeIfAbsent(key, k -> new StringBuilder());
                if (sb.length() < 20000) {
                    if (sb.length() > 0) {
                        sb.append("||");
                    }
                    sb.append(row.get("title")).append("。").append(row.get("content"));
                }
            }
            for (Map.Entry<String, StringBuilder> e : buckets.entrySet()) {
                redis.opsForHash().put("predict:texts", e.getKey(), e.getValue().toString());
            }
            log.info("预判统计桥刷新完成：日统计{}条，文本桶{}个", daily.size(), buckets.size());
        } catch (Exception e) {
            log.warn("预判统计桥刷新失败：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------ 预判执行

    /**
     * 执行预判：读取配置 → 刷新统计桥 → 调用 /ai/v1/predict → 结果入库 + AI 审计留痕
     * AI 服务不可用时降级：不抛异常，仅记录日志（预判数据保持上一周期）
     */
    public int runPrediction() {
        // 执行Prediction
        ensureConfig();
        PredictionConfig config = configMapper.selectFirst();
        if (config == null) {
            log.warn("预判参数配置不存在，跳过预判");
            return -1;
        }
        int historyDays = Integer.parseInt(config.getHistoryRange());
        refreshRedisStats(historyDays);
        String period = PERIOD_MAP.getOrDefault(config.getPredictPeriod(), "7_DAY");
        Optional<AiPredictResult> aiResult;
        try {
            aiResult = aiServiceClient.predict(
                    historyDays, period,
                    config.getConfidenceThreshold().doubleValue(), config.getClusterCount());
        } catch (Exception e) {
            // 连接失败等异常同样按降级处理（AiServiceClient 重试耗尽会抛异常，
            // 此处吞掉以维持"AI 不可用不抛异常"契约，防启动 Runner 失败/接口 500）
            aiResult = Optional.empty();
        }
        if (aiResult.isEmpty()) {
            // AI 降级：预判数据保持上一周期（不抛 500）
            auditLogService.recordAiGeneration("PREDICT", "AI生成·预判执行失败（已降级）",
                    null, "AI服务不可用", "none");
            log.warn("预判执行降级：AI 服务不可用");
            return -1;
        }
        AiPredictResult result = aiResult.get();
        List<PredictionResult> rows = result.hotspots().stream()
                .filter(h -> h.predictedCount() != null && h.predictedCount() > 0)
                .map(h -> toPredictionResult(config, h))
                .toList();
        if (!rows.isEmpty()) {
            resultMapper.insertBatch(rows);
            // 预判成果联动预警（需求 9(5)）
            warningService.scanByPrediction(rows);
        }
        // 趋势入库（需求 9(6)）
        List<PredictionTrend> trends = result.trend() == null ? List.of()
                : result.trend().stream()
                    .filter(t -> t.predicted() != null && t.predicted() > 0)
                    .map(t -> toPredictionTrend(period, t))
                    .toList();
        if (!trends.isEmpty()) {
            trendMapper.insertBatch(trends);
        }
        int hotCount = result.hotspots().size();
        auditLogService.recordAiGeneration("PREDICT",
                "AI生成·预判执行完成：周期" + period + "，热点" + hotCount + "个",
                null, "时序预测+热点聚类，统计桥历史" + config.getHistoryRange() + "天", "qwen-plus");
        log.info("预判执行完成：热点 {} 个，入库 {} 条，趋势 {} 个", hotCount, rows.size(), trends.size());
        return rows.size();
    }

    private PredictionResult toPredictionResult(PredictionConfig config, AiHotspot hotspot) {
        // 执行toPredictionResult操作
        PredictionResult r = new PredictionResult();
        r.setPeriod(PERIOD_MAP.getOrDefault(config.getPredictPeriod(), "7_DAY"));
        r.setCategory(hotspot.category());
        r.setSubCategory(hotspot.subCategory() == null || hotspot.subCategory().isBlank()
                ? null : hotspot.subCategory());
        r.setRegion(hotspot.region());
        r.setCountyName(hotspot.region());
        r.setPredictedCount(hotspot.predictedCount());
        r.setConfidence(hotspot.confidence() == null ? null : BigDecimal.valueOf(hotspot.confidence()));
        return r;
    }

    /** 统计桥三元组键：{category}:{sub_category}:{region}（sub_category 为空时占位为空串） */
    private String predictionKey(Object category, Object subCategory, Object region) {
        // 执行predictionKey操作
        String cat = category == null ? "" : String.valueOf(category);
        String sub = subCategory == null ? "" : String.valueOf(subCategory);
        String reg = region == null ? "" : String.valueOf(region);
        return cat + ":" + sub + ":" + reg;
    }

    private PredictionTrend toPredictionTrend(String period, AiTrendPoint point) {
        // 转换为趋势点
        PredictionTrend t = new PredictionTrend();
        t.setPeriod(period);
        t.setTrendDate(point.date());
        t.setPredicted(point.predicted());
        t.setConfidence(point.confidence() == null ? null : BigDecimal.valueOf(point.confidence()));
        return t;
    }

    /**
     * 每日定时预判（默认 01:17），周期可在 application.yaml 调整
     */
    @Scheduled(cron = "${app.prediction.cron:0 17 1 * * ?}")
    public void scheduledPrediction() {
        // 执行scheduledPrediction操作
        runPrediction();
    }

    /** 配置不存在时自动初始化默认行（P1-3），避免预判/配置读写 404 或跳过 */
    private void ensureConfig() {
        // 执行ensureConfig操作
        if (configMapper.selectFirst() == null) {
            PredictionConfig config = new PredictionConfig();
            config.setPredictPeriod("7DAY");
            config.setConfidenceThreshold(new BigDecimal("0.70"));
            config.setClusterCount(10);
            config.setHistoryRange("90");
            configMapper.insert(config);
            log.info("预判参数配置不存在，已自动初始化默认配置");
        }
    }

    // ------------------------------------------------------------ 预判结果查询

    /**
     * 预判结果查询（API 8.3）
     */
    public PageData<Map<String, Object>> page(String category, String region, String period,
                                              int page, int pageSize) {
        // 分页查询
        int offset = Math.max(0, (page - 1) * pageSize);
        List<PredictionResult> rows = resultMapper.selectPage(category, region, period, offset, pageSize);
        long total = resultMapper.countPage(category, region, period);
        List<Map<String, Object>> list = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("period", r.getPeriod());
            m.put("category", r.getCategory());
            m.put("sub_category", r.getSubCategory());
            m.put("region", r.getRegion());
            m.put("county_name", r.getCountyName());
            m.put("predicted_count", r.getPredictedCount());
            m.put("confidence", r.getConfidence());
            m.put("actual_count", r.getActualCount());
            m.put("hit_status", r.getHitStatus());
            m.put("prediction_time", r.getCreateTime());
            return m;
        }).toList();
        return PageData.of(list, total, page, pageSize);
    }

    /**
     * 预判动态下钻（预警管理融合页）：取最近一批预判结果，按区县/一级/二级/周期筛选后分组
     * - primary_hotspots：按一级分类聚合（各二级预测量求和）
     * - secondary_hotspots：按一级+二级分类列出（二级为空的聚合入一级，不在二级中单独展示）
     * - unpredictable：最近一批无任何可展示的热点（历史数据不足），前端据此提示“无法预测”
     */
    public Map<String, Object> query(String category, String subCategory, String region, String period) {
        // 预判动态下钻查询
        List<PredictionResult> rows = resultMapper.selectLatest(category, subCategory, region, period);

        // 一级分类聚合：category -> [总预测量, 记录数, 置信度和]
        Map<String, long[]> primaryAgg = new LinkedHashMap<>();
        Map<String, Double> primaryConfSum = new LinkedHashMap<>();
        List<Map<String, Object>> secondary = new java.util.ArrayList<>();

        for (PredictionResult r : rows) {
            long cnt = r.getPredictedCount() == null ? 0 : r.getPredictedCount();
            String cat = r.getCategory();
            long[] agg = primaryAgg.computeIfAbsent(cat, k -> new long[]{0, 0});
            agg[0] += cnt;
            agg[1] += 1;
            if (r.getConfidence() != null) {
                primaryConfSum.merge(cat, r.getConfidence().doubleValue(), Double::sum);
            }
            if (r.getSubCategory() != null && !r.getSubCategory().isBlank()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("category", cat);
                m.put("sub_category", r.getSubCategory());
                m.put("predicted_count", cnt);
                m.put("confidence", r.getConfidence());
                secondary.add(m);
            }
        }

        List<Map<String, Object>> primary = new java.util.ArrayList<>();
        for (Map.Entry<String, long[]> e : primaryAgg.entrySet()) {
            String cat = e.getKey();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", cat);
            m.put("predicted_count", e.getValue()[0]);
            m.put("confidence", primaryConfSum.containsKey(cat)
                    ? round4(primaryConfSum.get(cat) / Math.max(1, e.getValue()[1]))
                    : null);
            primary.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("primary_hotspots", primary);
        data.put("secondary_hotspots", secondary);
        data.put("unpredictable", primary.isEmpty() && secondary.isEmpty());
        return data;
    }

    // ------------------------------------------------------------ 预判参数配置

    /**
     * 预判参数配置（API 8.8），调整后即时生效（下一周期预判即采用新参数）
     */
    public Map<String, Object> updateConfig(PredictionConfigRequest request) {
        // 更新配置
        ensureConfig();
        PredictionConfig config = configMapper.selectFirst();
        if (config == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "预判参数配置不存在");
        }
        config.setPredictPeriod(request.predictPeriod());
        config.setConfidenceThreshold(BigDecimal.valueOf(request.confidenceThreshold()));
        if (request.clusterCount() != null) {
            config.setClusterCount(request.clusterCount());
        }
        if (request.historyRange() != null) {
            config.setHistoryRange(request.historyRange());
        }
        configMapper.update(config);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("predict_period", config.getPredictPeriod());
        data.put("confidence_threshold", config.getConfidenceThreshold());
        data.put("cluster_count", config.getClusterCount());
        data.put("history_range", config.getHistoryRange());
        return data;
    }

    public Map<String, Object> currentConfig() {
        // 执行currentConfig操作
        ensureConfig();
        PredictionConfig config = configMapper.selectFirst();
        Map<String, Object> data = new LinkedHashMap<>();
        if (config != null) {
            data.put("predict_period", config.getPredictPeriod());
            data.put("confidence_threshold", config.getConfidenceThreshold());
            data.put("cluster_count", config.getClusterCount());
            data.put("history_range", config.getHistoryRange());
        }
        return data;
    }

    // ------------------------------------------------------------ 预判效果评估

    /**
     * 预判效果评估（API 8.9）：预判结果与实际工单数据对比
     * 命中判定标准（文档未定义，采用）：实际工单数在预测值 ±20% 范围内计 HIT；
     * 实际工单数低于预测值 50% 计漏报（MISS）。
     */
    public Map<String, Object> accuracy() {
        // 执行accuracy操作
        List<PredictionResult> filled = resultMapper.selectFilled();
        if (filled.isEmpty()) {
            return Map.of("accuracy", 0.0, "hit_rate", 0.0, "miss_rate", 0.0);
        }
        int hit = 0;
        int miss = 0;
        double totalError = 0.0;
        for (PredictionResult r : filled) {
            double predicted = r.getPredictedCount() == null ? 0 : r.getPredictedCount();
            double actual = r.getActualCount() == null ? 0 : r.getActualCount();
            if (predicted <= 0) {
                continue;
            }
            totalError += Math.abs(actual - predicted) / predicted;
            if (Math.abs(actual - predicted) / predicted <= 0.2) {
                hit++;
            }
            if (actual < predicted * 0.5) {
                miss++;
            }
        }
        int total = Math.max(1, filled.size());
        double accuracy = Math.max(0, 1 - totalError / total);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accuracy", round2(accuracy));
        data.put("hit_rate", round2((double) hit / total));
        data.put("miss_rate", round2((double) miss / total));
        return data;
    }

    private double round2(double v) {
        // 执行round2操作
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private double round4(double v) {
        // 执行round4操作
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------ 预判回写（P0-2）

    /**
     * 预判周期结束后回填实际工单数与命中状态：
     * 仅处理 create_time + 周期天数已过去的记录，按 category 精确 + region 前缀聚合 work_order 实际数
     */
    public int backfillActual() {
        // 执行backfillActual操作
        List<PredictionResult> unfilled = resultMapper.selectUnfilled();
        if (unfilled.isEmpty()) {
            return 0;
        }
        int filled = 0;
        LocalDateTime now = LocalDateTime.now();
        for (PredictionResult r : unfilled) {
            int days = PERIOD_DAYS.getOrDefault(r.getPeriod(), 7);
            LocalDateTime start = r.getCreateTime() == null ? now.minusDays(days) : r.getCreateTime();
            if (start.plusDays(days).isAfter(now)) {
                continue;
            }
            long actual = adminWorkOrderMapper.countActualForPrediction(r.getCategory(), r.getSubCategory(), r.getRegion(), start, start.plusDays(days));
            resultMapper.updateActual(r.getId(), (int) actual, hitStatus(r.getPredictedCount(), (int) actual));
            filled++;
        }
        log.info("预判回填完成：待回填{}条，本轮回填{}条", unfilled.size(), filled);
        return filled;
    }

    /** 命中状态判定：实际值在预测值 ±20% 内计 HIT；低于预测量 50% 计 MISS（与 accuracy 口径一致） */
    private String hitStatus(Integer predicted, int actual) {
        // 执行hitStatus操作
        if (predicted == null || predicted <= 0) {
            return "MISS";
        }
        double p = predicted;
        if (Math.abs(actual - p) / p <= 0.2) {
            return "HIT";
        }
        return actual < p * 0.5 ? "MISS" : "HIT";
    }

    /** 每日定时回填（默认 01:00，先于 01:17 预判） */
    @Scheduled(cron = "${app.prediction.backfill-cron:0 0 1 * * ?}")
    public void scheduledBackfill() {
        // 执行scheduledBackfill操作
        try {
            backfillActual();
        } catch (Exception e) {
            log.warn("预判回填失败：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------ 预判趋势（P1-2）

    public Map<String, Object> trend(String period) {
        // 查询预判趋势
        String p = (period == null || period.isBlank()) ? "7_DAY" : period;
        List<Map<String, Object>> points = trendMapper.selectLatest(p).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", t.getTrendDate());
            m.put("predicted", t.getPredicted());
            m.put("confidence", t.getConfidence());
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", p);
        data.put("points", points);
        return data;
    }
}