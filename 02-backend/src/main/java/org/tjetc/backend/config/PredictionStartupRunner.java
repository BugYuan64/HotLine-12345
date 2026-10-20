package org.tjetc.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.tjetc.backend.service.admin.PredictionService;

/**
 * 启动时按配置执行一次预判（app.prediction.run-on-startup，演示模式）
 */
@Slf4j
@Component
public class PredictionStartupRunner implements ApplicationRunner {

    @Value("${app.prediction.run-on-startup:false}")
    private boolean runOnStartup;

    private final PredictionService predictionService;

    public PredictionStartupRunner(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 启动预判：失败仅记日志不阻断应用启动（AI 服务可能尚未就绪，就绪后可手动触发）
        if (runOnStartup) {
            log.info("启动预判任务（run-on-startup=true）");
            try {
                predictionService.runPrediction();
            } catch (Exception e) {
                log.warn("启动预判失败（已跳过，不影响应用启动）：{}", e.getMessage());
            }
        }
    }
}
