package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预判趋势表 prediction_trend（需求 9(6) 趋势折线图）
 */
@Data
@NoArgsConstructor
public class PredictionTrend {

    private Long id;
    /** 预判周期：DAY/3_DAY/7_DAY */
    private String period;
    /** 趋势日期（AI返回） */
    private String trendDate;
    /** 预测工单数 */
    private Integer predicted;
    private BigDecimal confidence;
    private LocalDateTime createTime;
}