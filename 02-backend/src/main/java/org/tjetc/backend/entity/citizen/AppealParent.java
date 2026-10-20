package org.tjetc.backend.entity.citizen;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 父诉求表 appeal_parent（需求 表 / API 4.1）
 * 市民一次提交的原始诉求，可被 AI 拆分为多个子诉求（appeal_child）。
 * 编号规则：AP + 时间戳 + 序号（父表内唯一）。
 */
@Data
@NoArgsConstructor
public class AppealParent {

    private Long id;
    /** 诉求编号（AP+时间戳+序号，父表内唯一） */
    private String appealNo;
    private String title;
    private String content;
    /** 诉求类别（8大类 code 或 OTHER，市民提交自带；AI 审核/分类可能纠正） */
    private String category;
    /** 市民姓名（提诉人） */
    private String citizenName;
    /** 市民手机号（提诉人） */
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
    /** 宽泛诉求开关（全区性不针对具体点位） */
    private Integer broadFlag;
    /** 诉求状态：PENDING_AI/PROCESSING/CHILDREN_GENERATED/WITHDRAWN */
    private String status;
    /** 子诉求数量（冗余计数） */
    private Integer childCount;
    /** 软删除标记 0=正常 1=已删除（删除保留历史，列表/查询过滤 is_deleted=0） */
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}