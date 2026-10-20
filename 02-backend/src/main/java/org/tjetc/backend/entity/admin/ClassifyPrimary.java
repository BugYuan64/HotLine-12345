package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一级分类表 classify_primary（需求 10.3(1) 分类体系维护）
 * 一级分类代码 + 名称 + 对应分派部门
 */
@Data
@NoArgsConstructor
public class ClassifyPrimary {

    private Long id;
    /** 一级分类代码（URBAN/TRAFFIC/MARKET/...） */
    private String code;
    /** 一级分类名称（城市管理/交通管理/...） */
    private String name;
    /** 对应分派部门（城管部门/交通部门/...） */
    private String department;
    /** 排序 */
    private Integer sortOrder;
    /** 状态：ENABLED/DISABLED */
    private String status;
}
