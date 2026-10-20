package org.tjetc.backend.entity.citizen;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 子诉求表 appeal_child（需求 表 / API 4.1）
 * AI 拆分出的单个事件，用于生成/归并工单的最小单位；通过 parent_id 关联父诉求。
 * 编号规则：AS + 时间戳 + 序号（子表内唯一）。
 */
@Data
@NoArgsConstructor
public class AppealChild {

    private Long id;
    /** 诉求编号（AS+时间戳+序号，子表内唯一） */
    private String appealNo;
    /** 父诉求ID（appeal_parent.id，1对多） */
    private Long parentId;
    private String title;
    private String content;
    /** 一级类别（8大类 code 或 OTHER，拆分时继承父诉求；AI 审核/分类可能纠正） */
    private String category;
    /** AI 分类二级标签（队列1 落库；建单与归并以二级分类为准） */
    private String subCategory;
    /** AI 分类置信度 0-1（队列1 落库） */
    private java.math.BigDecimal confidence;
    /** 需人工复核（1=是；AI 分类降级/低置信度时队列1 置 1） */
    private Integer needReview;
    /** 清洗日志ID（sys_audit_log.id，清洗细则在系统审计中查看） */
    private Long cleanLogId;
    /** 市民姓名（冗余自父诉求） */
    private String citizenName;
    /** 市民手机号（冗余自父诉求） */
    private String citizenPhone;
    private String province;
    private String city;
    private String district;
    /** 区县编码（sys_region.region_code，level=COUNTY） */
    private String districtCode;
    private String street;
    /** 街道/镇/乡编码（sys_region.region_code，level∈STREET/TOWN/TOWNSHIP） */
    private String streetCode;
    private String community;
    /** 详细地址补充 */
    private String addressDetail;
    /** 完整地址（district + street + address_detail 拼接，展示/分发用） */
    private String region;
    /** 宽泛诉求开关 */
    private Integer broadFlag;
    /** 子诉求状态：PROCESSING/ORDERED/WITHDRAWN */
    private String status;
    /** 软删除标记 0=正常 1=已删除（删除保留历史，列表/查询过滤 is_deleted=0） */
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}