package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 预警记录表 warning_record（需求 表10/表15 / API 8.2）
 */
@Data
@NoArgsConstructor
public class WarningRecord {

    private Long id;
    /** 预警编号（WR+日期+序号） */
    private String warningNo;
    /** 规则ID */
    private Long ruleId;
    /** 预警等级：GENERAL/SEVERE/URGENT */
    private String warningLevel;
    /** 一级分类（NULL=全部分类） */
    private String category;
    /** 二级分类标签（NULL=无/全部分类） */
    private String subCategory;
    /** 涉及辖区 */
    private String region;
    /** 区县编码 */
    private String countyCode;
    /** 区县名称 */
    private String countyName;
    /** 街道/镇/乡编码 */
    private String streetCode;
    /** 街道/镇/乡名称 */
    private String streetName;
    /** 详细地址 */
    private String addressDetail;
    /** 是否宽泛诉求 */
    private Integer isBroad;
    /** 归属部门（来自规则 push_targets 中的部门代码，逗号分隔；NULL=仅推送 ADMIN 不归属部门） */
    private String department;
    /** 触发时间 */
    private LocalDateTime triggerTime;
    /** 审核状态：PENDING待审核/SENT已发送/REJECTED已驳回 */
    private String status;
    /** 管理端审核时间（发送/驳回时刻） */
    private LocalDateTime reviewTime;
    /** 累计关注度（窗口内命中工单 attention_count 求和） */
    private Integer relatedAttention;
    /** AI总结内容（AI生成·置信度X%标识） */
    private String aiSummary;
    /** 应对措施 */
    private String measure;
    /** 参考优秀处置案例编号（逗号分隔，预警建议 RAG 溯源展示） */
    private String refCases;
    /** 响应状态：UNRESPONDED/RESPONDED */
    private String respondStatus;
    /** 响应时间 */
    private LocalDateTime respondTime;
}
