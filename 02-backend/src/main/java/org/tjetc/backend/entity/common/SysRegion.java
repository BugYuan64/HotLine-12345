package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 区划字典表 sys_region（秦皇岛市固定，无省市级）：
 * 自关联结构：区县(COUNTY) 为顶层，街道(STREET)/镇(TOWN)/乡(TOWNSHIP) 通过 parent_code 指向区县。
 */
@Data
@NoArgsConstructor
public class SysRegion {

    private Long id;
    /** 区划编码（区县6位/乡镇级9位，唯一） */
    private String regionCode;
    /** 区划名称（如 海港区 / 海滨路街道 / 东港镇 / 渤海乡） */
    private String regionName;
    /** 层级：COUNTY区县/STREET街道/TOWN镇/TOWNSHIP乡 */
    private String level;
    /** 上级区划编码（乡镇级指向区县，区县为NULL） */
    private String parentCode;
    /** 排序号 */
    private Integer sortOrder;
    /** 状态：ENABLED/DISABLED */
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}