package org.tjetc.backend.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.dto.admin.PredictionConfigRequest;
import org.tjetc.backend.service.admin.PredictionService;

import java.util.Map;

/**
 * 管理端诉求预判接口（API 8.3/8.8/8.9）：
 * 预判结果查询 / 预判参数配置 / 预判效果评估
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
public class AdminPredictionController {

    private final PredictionService predictionService;

    /**
     * 预判结果查询（API 8.3）
     */
    @GetMapping("/predictions")
    public Result<Map<String, Object>> predictions(@RequestParam(required = false) String category,
                                                   @RequestParam(required = false) String region,
                                                   @RequestParam(required = false) String period,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 预测ions
        PageData<Map<String, Object>> data =
                predictionService.page(category, region, period, page, pageSize);
        return Result.page(data);
    }

    /**
     * 预判动态下钻（预警管理融合页）：按区县/一级/二级/周期筛选最近一批预判结果并分组
     */
    @GetMapping("/predictions/query")
    public Result<Map<String, Object>> query(@RequestParam(required = false) String category,
                                             @RequestParam(required = false) String sub_category,
                                             @RequestParam(required = false) String region,
                                             @RequestParam(required = false) String period) {
        // 预判动态下钻
        return Result.ok(predictionService.query(category, sub_category, region, period));
    }

    /**
     * 预判参数配置（API 8.8）：调整后即时生效
     */
    @PutMapping("/prediction/config")
    public Result<Map<String, Object>> updateConfig(@Valid @RequestBody PredictionConfigRequest request) {
        // 更新配置
        return Result.ok(predictionService.updateConfig(request));
    }

    /**
     * 手动重新预判（无需重启后端）：执行一次预判并返回本次热点数与是否降级
     */
    @PostMapping("/predictions/run")
    public Result<Map<String, Object>> run() {
        // 手动重新预判
        int hotspots = predictionService.runPrediction();
        boolean degraded = hotspots < 0;
        return Result.ok(Map.of("hotspots", Math.max(0, hotspots), "degraded", degraded));
    }

    /**
     * 预判参数配置查询（配合 8.8 配置回显）
     */
    @GetMapping("/prediction/config")
    public Result<Map<String, Object>> currentConfig() {
        // 执行currentConfig操作
        return Result.ok(predictionService.currentConfig());
    }

    /**
     * 预判效果评估（API 8.9）：准确率 / 命中率 / 漏报率
     */
    @GetMapping("/prediction/accuracy")
    public Result<Map<String, Object>> accuracy() {
        // 执行accuracy操作
        return Result.ok(predictionService.accuracy());
    }

    /**
     * 预判趋势查询（P1-2 / 需求 9(6) 趋势折线图）
     */
    @GetMapping("/prediction/trend")
    public Result<Map<String, Object>> trend(@RequestParam(required = false) String period) {
        // 查询趋势
        return Result.ok(predictionService.trend(period));
    }
}