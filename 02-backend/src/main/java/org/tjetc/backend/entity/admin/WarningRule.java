package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 预警规则表 warning_rule（需求 表10 / API 8.1）
 */
@Data
@NoArgsConstructor
public class WarningRule {

    private Long id;
    /** 规则名称 */
    private String ruleName;
    /** 时间维度：DAY/3_DAY/7_DAY */
    private String timeDimension;
    /** 一级分类（NULL=全部分类） */
    private String category;
    /** 二级分类标签（NULL=该一级下全部分类） */
    private String subCategory;
    /** 辖区（NULL=全部辖区） */
    private String region;
    /** 区县编码（NULL=全部辖区） */
    private String countyCode;
    /** 街道编码（NULL=该区县全部/全部辖区） */
    private String streetCode;
    /** 数量阈值 */
    private Integer countThreshold;
    /** 预警等级：GENERAL/SEVERE/URGENT */
    private String warningLevel;
    /** 推送对象（旧格式兼容，逗号分隔）：ADMIN/纯分类code/区县:分类；新数据用下方双限制字段 */
    private String pushTargets;
    /** 辖区限制（逗号分隔区县code，空=全部区县） */
    private String pushCounties;
    /** 部门类别限制（逗号分隔分类code，空=全部部门类别） */
    private String pushCategories;
    /** 状态：ENABLED/DISABLED/DRAFT（草稿不参与扫描，人工确认后置 ENABLED） */
    private String status;
    private LocalDateTime createTime;
}
