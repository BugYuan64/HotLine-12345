package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户表 sys_user（需求 表2：内部工作人员，密码 bcrypt 加密）
 */
@Data
@NoArgsConstructor
public class SysUser {

    private Long id;
    /** 工号（6-20位字母数字组合，唯一） */
    private String jobNo;
    private String name;
    private String phone;
    /** 邮箱（注册邮箱验证码功能） */
    private String email;
    /** 密码（bcrypt 加盐哈希，明文不落库） */
    private String password;
    private String company;
    /** 归属类型：STREET/URBAN/PROPERTY/LIVELIHOOD/ADMIN */
    private String userType;
    /** 对应辖区/部门 */
    private String region;
    /** 辖区编码（街道办账号存街道级region_code，用于与工单street_code匹配；部门账号不使用） */
    private String regionCode;
    /** 账号状态：ENABLED/DISABLED（与审核状态相互独立） */
    private String status;
    /** 审核状态：PENDING/APPROVED/REJECTED */
    private String auditStatus;
    /** 逻辑删除：0正常/1已删除 */
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
