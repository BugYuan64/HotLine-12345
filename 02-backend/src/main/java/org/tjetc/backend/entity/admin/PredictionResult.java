package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预判结果表 prediction_result（需求 表8 / API 8.3）
 */
@Data
@NoArgsConstructor
public class PredictionResult {

    private Long id;
    /** 预判周期：DAY/3_DAY/7_DAY */
    private String period;
    private String category;
    /** 二级分类标签（NULL=未区分到二级） */
    private String subCategory;
    private String region;
    /** 区县编码 */
    private String countyCode;
    /** 区县名称 */
    private String countyName;
    /** 街道/镇/乡编码 */
    private String streetCode;
    /** 街道/镇/乡名称 */
    private String streetName;
    private Integer predictedCount;
    private BigDecimal confidence;
    /** 实际工单数（周期结束后回填） */
    private Integer actualCount;
    /** 命中状态：HIT/MISS（评估后回填） */
    private String hitStatus;
    private LocalDateTime createTime;
}
