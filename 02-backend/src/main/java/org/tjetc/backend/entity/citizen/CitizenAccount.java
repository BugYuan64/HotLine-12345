package org.tjetc.backend.entity.citizen;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 市民账号（citizen_account）：手机号/邮箱登录、个人资料、诉求提交默认值来源
 */
@Data
public class CitizenAccount {

    private Long id;
    /** 手机号（登录账号） */
    private String phone;
    /** 登录密码（bcrypt） */
    private String password;
    /** 姓名 */
    private String name;
    /** 邮箱（邮箱登录账号） */
    private String email;
    /** 辖区区划编码 */
    private String districtCode;
    /** 辖区名称 */
    private String district;
    /** 街道/镇/乡区划编码 */
    private String streetCode;
    /** 街道/镇/乡名称 */
    private String street;
    /** 详细住址（选填） */
    private String addressDetail;
    /** 账号创建时间 */
    private LocalDateTime createTime;
}
