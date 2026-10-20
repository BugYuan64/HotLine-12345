package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预判参数配置表 prediction_config（API 8.8，单行配置）
 */
@Data
@NoArgsConstructor
public class PredictionConfig {

    private Long id;
    /** 预测周期：DAY/3DAY/7DAY */
    private String predictPeriod;
    /** 置信度阈值（0~1） */
    private BigDecimal confidenceThreshold;
    /** 热点聚类数量 */
    private Integer clusterCount;
    /** 历史数据范围（30/90/180/365天） */
    private String historyRange;
    private LocalDateTime updateTime;
}
