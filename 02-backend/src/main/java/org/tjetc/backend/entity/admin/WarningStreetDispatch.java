package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 预警街道办分发关系表 warning_street_dispatch：
 * 一张预警记录可按街道/乡镇分发多条，每条对应一个街道办的可见范围与响应闭环。
 */
@Data
@NoArgsConstructor
public class WarningStreetDispatch {

    private Long id;
    /** 预警记录ID（warning_record.id） */
    private Long warningId;
    /** 乡镇级区划编码（sys_region.region_code，无匹配时为空） */
    private String streetCode;
    /** 街道/镇/乡名称（与街道办账号 region 同口径） */
    private String streetOffice;
    /** 所属区县名（冗余展示） */
    private String countyName;
    /** 响应状态：UNRESPONDED未响应/RESPONDED已响应 */
    private String respondStatus;
    private LocalDateTime dispatchTime;
    private LocalDateTime respondTime;
    private Long respondUserId;
    private String respondUserName;
}