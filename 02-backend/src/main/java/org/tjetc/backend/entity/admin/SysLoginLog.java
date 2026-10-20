package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 登录日志表 sys_login_log（需求 表2/表17 / API 9.11）
 */
@Data
@NoArgsConstructor
public class SysLoginLog {

    private Long id;
    /** 用户ID（市民登录为NULL） */
    private Long userId;
    /** 登录方式：JOB_NO_PASSWORD/SMS_CODE */
    private String loginMethod;
    private String ip;
    private String device;
    private LocalDateTime loginTime;
    /** 登录结果：SUCCESS/FAIL */
    private String result;
}
