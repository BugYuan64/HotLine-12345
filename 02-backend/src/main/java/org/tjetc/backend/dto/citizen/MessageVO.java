package org.tjetc.backend.dto.citizen;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息列表项（API 4.6）
 */
@Data
@NoArgsConstructor
public class MessageVO {

    private Long id;
    /** 消息类型 */
    private String msgType;
    /** 标题 */
    private String title;
    /** 内容 */
    private String content;
    /** 是否已读：0未读/1已读 */
    private Integer isRead;
    /** 通知渠道 */
    private String channel;
    /** 创建时间 */
    private String createTime;
}
