package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分类体系表 classify_category（需求 表7/表9 / API 7.x）
 */
@Data
@NoArgsConstructor
public class ClassifyCategory {

    private Long id;
    /** 一级分类：URBAN（城管）/PROPERTY（物业）/LIVELIHOOD（民生） */
    private String primaryCategory;
    /** 二级标签（如占道经营/违章搭建） */
    private String subLabel;
    /** 排序 */
    private Integer sortOrder;
}
