package org.tjetc.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关（需求 9 诉求预判周期执行、需求 21/25 超时检测等）
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
