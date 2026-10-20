package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 街道办端通知收件箱 street_notice（通知公告四端拆分）
 * 管理端/部门端发布通知后按接收目标扇出的街道办级快照（一行 = 一条通知 × 一个街道办）。
 * 已读为街道办级粒度：街道办用户点开详情即整行置已读。
 */
@Data
@NoArgsConstructor
public class StreetNotice {

    private Long id;
    /** 源公告ID（admin_notice.id） */
    private Long noticeId;
    private String title;
    private String content;
    private String summary;
    private String publisher;
    /** 发布者类型：ADMIN/DEPT/STREET */
    private String publisherType;
    private String publisherDept;
    private Integer topFlag;
    private Integer status;
    /** 接收街道办名（sys_region 街道级名称） */
    private String streetName;
    /** 街道办级已读：0 未读 / 1 已读 */
    private Integer isRead;
    private LocalDateTime readTime;
    /** 到达时间（扇出时间） */
    private LocalDateTime createTime;
}
