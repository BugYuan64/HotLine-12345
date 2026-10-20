package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 站内消息表 sys_message（需求 表27 / API 4.6-4.8）
 */
@Data
@NoArgsConstructor
public class SysMessage {

    private Long id;
    /** 接收市民手机号（市民消息） */
    private String phone;
    /** 接收用户ID（工作人员消息） */
    private Long userId;
    /** 消息类型：ORDER_CREATED/ORDER_DISPATCHED/ORDER_RETURNED/ORDER_CLOSED/ORDER_REOPENED/WARNING */
    private String msgType;
    private String title;
    /** 内容（含变更原因与后续进展） */
    private String content;
    /** 是否已读：0未读/1已读 */
    private Integer isRead;
    /** 通知渠道：IN_APP/SMS/APP_PUSH */
    private String channel;
    private LocalDateTime createTime;
}
