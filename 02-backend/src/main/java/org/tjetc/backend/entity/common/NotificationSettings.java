package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知偏好设置表 notification_settings（需求 表28 / API 4.8）
 */
@Data
@NoArgsConstructor
public class NotificationSettings {

    private Long id;
    /** 市民手机号 */
    private String phone;
    /** 是否接收短信通知（进度通知默认站内，办结通知站内+短信） */
    private Integer smsEnabled;
}
