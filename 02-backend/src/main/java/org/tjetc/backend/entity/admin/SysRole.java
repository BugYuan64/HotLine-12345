package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色表 sys_role（需求 表3：系统管理员/城管/物业/民生部门人员/街道办人员/审核专员/督办专员）
 */
@Data
@NoArgsConstructor
public class SysRole {

    private Long id;
    private String code;
    private String name;
    /** 数据权限范围（辖区/分类） */
    private String scope;
    private String description;
}
