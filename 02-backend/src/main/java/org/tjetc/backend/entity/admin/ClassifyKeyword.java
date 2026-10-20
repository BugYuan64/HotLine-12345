package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 分类关键词表 classify_keyword（需求 表7/表9 / API 7.1）
 */
@Data
@NoArgsConstructor
public class ClassifyKeyword {

    private Long id;
    /** 分类ID（classify_category.id） */
    private Long categoryId;
    /** 关键词 */
    private String keyword;
    /** 权重（权重越高匹配优先级越高） */
    private BigDecimal weight;
    /** 状态：ENABLED/DISABLED */
    private String status;
}
