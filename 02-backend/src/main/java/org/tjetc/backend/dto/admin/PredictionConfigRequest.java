package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 预判参数配置（API 8.8）
 */
public record PredictionConfigRequest(
        @NotNull(message = "预测周期不能为空")
        @Pattern(regexp = "^(DAY|3DAY|7DAY)$", message = "预测周期必须为DAY/3DAY/7DAY之一")
        @JsonProperty("predict_period")
        String predictPeriod,

        @NotNull(message = "置信度阈值不能为空")
        @DecimalMin(value = "0.0", message = "置信度阈值范围为0~1")
        @DecimalMax(value = "1.0", message = "置信度阈值范围为0~1")
        @JsonProperty("confidence_threshold")
        Double confidenceThreshold,

        @JsonProperty("cluster_count")
        Integer clusterCount,

        @Pattern(regexp = "^(30|90|180|365)$", message = "历史数据范围必须为30/90/180/365天之一")
        @JsonProperty("history_range")
        String historyRange) {
}
