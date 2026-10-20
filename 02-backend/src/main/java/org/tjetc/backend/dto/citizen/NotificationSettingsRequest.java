package org.tjetc.backend.dto.citizen;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知设置请求（API 4.8）
 */
@Data
@NoArgsConstructor
public class NotificationSettingsRequest {

    /** 是否接收短信通知：1=接收 0=仅站内通知 */
    private Integer smsEnabled;
}
