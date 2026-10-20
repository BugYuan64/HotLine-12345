package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tjetc.backend.util.ExcelUtil;
import org.tjetc.backend.mapper.citizen.AppealChildMapper;
import org.tjetc.backend.mapper.citizen.AppealParentMapper;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局数据统计（需求 14 / API 8.4/8.5）：
 * 工单总量概览 / 分类统计 / 等级统计 / 诉求趋势 / 看板数据 + Excel 报表导出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final AppealChildMapper appealChildMapper;
    private final AppealParentMapper appealParentMapper;

    /**
     * 全局数据统计看板（API 8.4）：核心指标 + 分类/等级分布 + 趋势 + 统计概要
     * 统计概要当前为程序化计算生成（AI 总结在 P2 智能问答接入后替换，字段名保持 ai_summary 与文档一致）
     * 口径约定：诉求总数（appeal_total）按父诉求（appeal_parent）创建时间统计；
     * 工单数、分类/等级/状态分布、趋势均按工单（work_order）统计
     */
    public Map<String, Object> summary(String period, String category, String region) {
        // 数据汇总
        LocalDateTime start = periodStart(period);
        long appealTotal = appealParentMapper.countByStart(start);
        long orderTotal = adminWorkOrderMapper.countAll(start, category, region);
        long pendingConfirm = adminWorkOrderMapper.countByStatus("PENDING_DISPATCH", start, category, region);
        long pendingUpdate = adminWorkOrderMapper.countByStatus("PENDING_UPDATE", start, category, region);
        long assigned = adminWorkOrderMapper.countAssigned(start, category, region);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appeal_total", appealTotal);
        data.put("order_total", orderTotal);
        data.put("pending_confirm", pendingConfirm);
        data.put("pending_update", pendingUpdate);
        data.put("assigned", assigned);
        data.put("dispatch_rate", orderTotal == 0 ? 0 : round4((double) assigned / orderTotal));
        data.put("category_distribution", adminWorkOrderMapper.categoryDistribution(start, category, region));
        data.put("status_distribution", adminWorkOrderMapper.statusDistribution(start, category, region));
        data.put("category_done_distribution", adminWorkOrderMapper.categoryDoneDistribution(start, category, region));
        data.put("level_distribution", adminWorkOrderMapper.levelDistribution(start, category, region));
        data.put("trend", adminWorkOrderMapper.trendDaily(start, category, region));
        data.put("trend_done", adminWorkOrderMapper.trendDoneDaily(start, category, region));
        boolean filtered = hasDimensionFilter(category, region);
        data.put("ai_summary", buildSummary(period, filtered, appealTotal, orderTotal, pendingConfirm,
                assigned, categoryDistributionMap(start, category, region)));
        return data;
    }

    /**
     * 统计报表导出（API 8.5）：核心指标/分类分布/等级分布/趋势四个工作表
     * （由 Controller 流式回写 HTTP 响应，零落盘）
     */
    public XSSFWorkbook report(String period, String category, String region) {
        // 生成报表
        Map<String, Object> s = summary(period, category, region);
        XSSFWorkbook workbook = ExcelUtil.createWorkbook();

        Sheet sheet1 = workbook.createSheet("核心指标");
        ExcelUtil.writeHeader(sheet1, new String[]{"指标", "数值"});
        boolean filtered = hasDimensionFilter(category, region);
        List<String[]> metrics = new ArrayList<>();
        if (!filtered) {
            metrics.add(new String[]{"诉求总数", String.valueOf(s.get("appeal_total"))});
        }
        metrics.add(new String[]{"工单总数", String.valueOf(s.get("order_total"))});
        metrics.add(new String[]{"待分派工单数", String.valueOf(s.get("pending_confirm"))});
        metrics.add(new String[]{"待更新工单数", String.valueOf(s.get("pending_update"))});
        metrics.add(new String[]{"已分配数量", String.valueOf(s.get("assigned"))});
        metrics.add(new String[]{"分派完成率", String.valueOf(s.get("dispatch_rate"))});
        for (int i = 0; i < metrics.size(); i++) {
            ExcelUtil.writeRow(sheet1, i + 1, new Object[]{metrics.get(i)[0], metrics.get(i)[1]});
        }
        ExcelUtil.autoSize(sheet1, 2);

        Sheet sheet2 = workbook.createSheet("分类分布");
        ExcelUtil.writeHeader(sheet2, new String[]{"分类", "数量"});
        List<Map<String, Object>> catRows = new ArrayList<>();
        for (Map<String, Object> r : castList(s.get("category_distribution"))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", categoryName(String.valueOf(r.get("category"))));
            row.put("cnt", r.get("cnt"));
            catRows.add(row);
        }
        writeRows(sheet2, catRows, new String[]{"category", "cnt"});

        Sheet sheet3 = workbook.createSheet("等级分布");
        ExcelUtil.writeHeader(sheet3, new String[]{"等级", "数量"});
        writeRows(sheet3, castList(s.get("level_distribution")), new String[]{"level", "cnt"});

        Sheet sheet4 = workbook.createSheet("工单趋势");
        ExcelUtil.writeHeader(sheet4, new String[]{"日期", "数量"});
        writeRows(sheet4, castList(s.get("trend")), new String[]{"d", "cnt"});

        return workbook;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object obj) {
        // 执行castList操作
        if (obj instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private void writeRows(Sheet sheet, List<Map<String, Object>> rows, String[] keys) {
        // 执行writeRows操作
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            ExcelUtil.writeRow(sheet, i + 1, new Object[]{r.get(keys[0]), r.get(keys[1])});
        }
        ExcelUtil.autoSize(sheet, 2);
    }

    private Map<String, Long> categoryDistributionMap(LocalDateTime start, String category, String region) {
        // 执行categoryDistributionMap操作
        Map<String, Long> map = new LinkedHashMap<>();
        for (Map<String, Object> r : adminWorkOrderMapper.categoryDistribution(start, category, region)) {
            map.put(String.valueOf(r.get("category")), toLong(r.get("cnt")));
        }
        return map;
    }

    /** 统计概要（程序化计算，字段保持 ai_summary 与文档一致；P2 智能问答接入后由 AI 生成） */
    private String buildSummary(String period, boolean filtered, long appealTotal, long orderTotal,
                                long pendingConfirm, long assigned, Map<String, Long> categoryDist) {
        // 构建Summary
        String periodLabel = switch (period == null ? "DAY" : period) {
            case "WEEK" -> "近7天";
            case "HALF_MONTH" -> "近15天";
            case "MONTH" -> "近30天";
            default -> "今日";
        };
        StringBuilder sb = new StringBuilder(periodLabel);
        if (!filtered) {
            sb.append("共受理诉求").append(appealTotal).append("件，");
        }
        sb.append("生成工单").append(orderTotal)
                .append("件，其中待分派").append(pendingConfirm).append("件，已分配").append(assigned).append("件");
        if (!filtered && !categoryDist.isEmpty()) {
            sb.append("；分类分布：");
            List<String> parts = new ArrayList<>();
            categoryDist.forEach((c, n) -> parts.add(categoryName(c) + n + "件"));
            sb.append(String.join("，", parts));
        }
        return sb.toString();
    }

    private String categoryName(String c) {
        // 执行categoryName操作
        return switch (c == null ? "" : c) {
            case "URBAN" -> "城市管理";
            case "TRAFFIC" -> "交通管理";
            case "MARKET" -> "市场监管";
            case "ENVIRONMENT" -> "环境保护";
            case "HOUSING" -> "住房保障";
            case "LABOR" -> "劳动保障";
            case "PUBLIC" -> "公共服务";
            case "HEALTH" -> "医疗卫生";
            default -> c; // 历史遗留分类（如 PROPERTY/LIVELIHOOD）原样显示
        };
    }

    private long toLong(Object o) {
        // 执行toLong操作
        if (o == null) {
            return 0;
        }
        return Long.parseLong(String.valueOf(o));
    }

    /** 是否存在维度筛选（部门/分类 或 辖区），用于决定诉求总量是否展示/汇报 */
    private boolean hasDimensionFilter(String category, String region) {
        return (category != null && !category.isBlank()) || (region != null && !region.isBlank());
    }

    private LocalDateTime periodStart(String period) {
        // 执行periodStart操作
        return switch (period == null ? "DAY" : period) {
            case "WEEK" -> LocalDateTime.now().minusDays(7);
            case "HALF_MONTH" -> LocalDateTime.now().minusDays(15);
            case "MONTH" -> LocalDateTime.now().minusDays(30);
            default -> LocalDate.now().atStartOfDay(); // 「今日」= 自然日（今天0点起）
        };
    }

    private double round4(double v) {
        // 执行round4操作
        return Math.round(v * 10000.0) / 10000.0;
    }
}