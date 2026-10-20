package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 分类规则表 classify_rule（需求 表7/表9 / API 7.2）
 */
@Data
@NoArgsConstructor
public class ClassifyRule {

    private Long id;
    /** 分类ID（classify_category.id，每个一级分类唯一） */
    private Long categoryId;
    /** 关键词命中数量阈值 */
    private Integer hitCountThreshold;
    /** 权重求和阈值 */
    private BigDecimal weightSumThreshold;
}
