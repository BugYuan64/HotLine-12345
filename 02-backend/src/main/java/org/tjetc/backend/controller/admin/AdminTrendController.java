package org.tjetc.backend.controller.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.service.admin.TrendService;

import java.util.Map;

/**
 * 诉求趋势对比接口（预警与预判页折线图）：
 * 按一级/二级分类 + 时间窗口（近 7/15/30 天）返回每日诉求量序列
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
public class AdminTrendController {

    private final TrendService trendService;

    /**
     * 每日诉求趋势（预警与预判页「诉求趋势对比」折线图数据源）
     *
     * @param days        近 N 天（7/15/30，默认 15）
     * @param category    一级分类 code（不传 = 不过滤）
     * @param subCategory 二级分类名（不传 = 该一级整体）
     * @param region      区县名（不传 = 不过滤；按前缀匹配只筛到区级）
     */
    @GetMapping("/trends/daily")
    public Result<Map<String, Object>> dailyTrend(@RequestParam(defaultValue = "15") Integer days,
                                                  @RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String subCategory,
                                                  @RequestParam(required = false) String region) {
        return Result.ok(trendService.dailyTrend(days, category, subCategory, region));
    }
}
