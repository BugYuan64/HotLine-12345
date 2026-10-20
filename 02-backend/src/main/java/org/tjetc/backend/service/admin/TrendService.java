package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.tjetc.backend.mapper.admin.TrendMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 诉求趋势对比服务（预警与预判页折线图）：
 * 按一级/二级分类 + 近 N 天返回每日诉求量，缺失日期补 0，保证折线连续
 */
@Service
@RequiredArgsConstructor
public class TrendService {

    private final TrendMapper trendMapper;

    /**
     * 每日诉求趋势
     *
     * @param days        近 N 天（1~90，超界自动收敛）
     * @param category    一级分类 code（空 = 不过滤）
     * @param subCategory 二级分类名（空 = 该一级整体）
     * @param region      区县名（空 = 不过滤；按前缀匹配只筛到区级）
     * @return { dates: [MM-DD...]（升序，截止今天）, values: [每日数量...] }
     */
    public Map<String, Object> dailyTrend(Integer days, String category, String subCategory, String region) {
        // 天数收敛，防止恶意大窗口拖垮查询
        int safeDays = Math.min(Math.max(days == null ? 15 : days, 1), 90);

        // 查询实际有数据的日期计数
        List<Map<String, Object>> rows = trendMapper.selectDailyCounts(safeDays, category, subCategory, region);
        Map<String, Integer> countByDate = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String d = String.valueOf(row.get("stat_date"));
            Number c = (Number) row.get("stat_count");
            countByDate.put(d, c == null ? 0 : c.intValue());
        }

        // 生成完整日期序列（近 N 天含今天），缺失日期补 0
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate today = LocalDate.now();
        List<String> dates = new ArrayList<>(safeDays);
        List<Integer> values = new ArrayList<>(safeDays);
        for (int i = safeDays - 1; i >= 0; i--) {
            String full = today.minusDays(i).format(fmt);
            dates.add(full.substring(5)); // 截取 MM-DD 供图表 x 轴展示
            values.add(countByDate.getOrDefault(full, 0));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("dates", dates);
        result.put("values", values);
        return result;
    }
}
