package org.tjetc.backend.controller.admin;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.service.admin.StatsService;
import org.tjetc.backend.util.ExcelUtil;

import java.io.IOException;
import java.util.Map;

/**
 * 管理端全局数据统计接口（API 8.4/8.5）：
 * 统计看板（核心指标/分类分布/等级分布/趋势/统计概要）+ 统计报表 Excel 导出
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
public class AdminStatsController {

    private final StatsService statsService;

    /** 全局数据统计看板（API 8.4）：统计周期 DAY/WEEK/MONTH */
    @GetMapping("/stats/summary")
    public Result<Map<String, Object>> summary(@RequestParam(required = false) String period,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String region) {
        // 数据汇总
        return Result.ok(statsService.summary(period, category, region));
    }

    /** 统计报表导出（API 8.5）：按当前筛选条件流式回写 Excel（零落盘） */
    @GetMapping("/stats/report")
    public void report(@RequestParam(required = false) String period,
                       @RequestParam(required = false) String category,
                       @RequestParam(required = false) String region,
                       HttpServletResponse response) throws IOException {
        // 生成报表
        XSSFWorkbook workbook = statsService.report(period, category, region);
        ExcelUtil.writeResponse(workbook, "stats_report.xlsx", response);
    }
}