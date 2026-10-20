package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 优秀处置案例库表 warning_case（预警建议 RAG 检索来源，人工维护）
 */
@Data
@NoArgsConstructor
public class WarningCase {

    private Long id;
    /** 案例编号（WC+日期+序号） */
    private String caseNo;
    /** 问题标题 */
    private String title;
    /** 一级分类（URBAN/PROPERTY/LIVELIHOOD/...） */
    private String category;
    /** 二级分类标签 */
    private String subCategory;
    /** 所属辖区 */
    private String region;
    /** 区县编码 */
    private String countyCode;
    /** 区县名称 */
    private String countyName;
    /** 街道/镇/乡编码 */
    private String streetCode;
    /** 街道/镇/乡名称 */
    private String streetName;
    /** 诉求描述（用于检索匹配的问题侧文本） */
    private String content;
    /** 处置方案（优秀做法） */
    private String measurePlan;
    /** 处置效果/结果 */
    private String measureResult;
    /** 来源：MANUAL=人工录入 */
    private String source;
    /** 状态：ENABLED/DISABLED */
    private String status;
    /** 向量ID（case:v:<id>） */
    private String vectorId;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
}