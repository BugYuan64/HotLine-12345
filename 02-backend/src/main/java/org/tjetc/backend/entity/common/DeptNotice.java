package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 部门端通知收件箱 dept_notice（通知公告四端拆分）
 * 管理端/部门端发布通知后按接收目标扇出的部门级快照（一行 = 一条通知 × 一个部门）。
 * 已读为部门级粒度：部门内任一用户点开详情即整行置已读。
 * 上级（管理端/部门）编辑时本表同步内容，下架/重发/删除由发布方联动本表。
 */
@Data
@NoArgsConstructor
public class DeptNotice {

    private Long id;
    /** 源公告ID（admin_notice.id，管理端编辑/下架/重发/删除联动键） */
    private Long noticeId;
    private String title;
    private String content;
    private String summary;
    /** 发布人姓名（脱敏/可空） */
    private String publisher;
    /** 发布者类型：ADMIN/DEPT/STREET */
    private String publisherType;
    /** 发布部门code（DEPT 时填） */
    private String publisherDept;
    /** 是否置顶：1 置顶 / 0 普通 */
    private Integer topFlag;
    /** 状态：1 展示中 / 0 已下架（发布方下架时本表同步） */
    private Integer status;
    /** 接收部门code（sys_user.user_type 同源，如 URBAN） */
    private String deptCode;
    /** 部门级已读：0 未读 / 1 已读（任一部门用户点开详情即置 1） */
    private Integer isRead;
    /** 首次已读时间 */
    private LocalDateTime readTime;
    /** 到达时间（扇出时间） */
    private LocalDateTime createTime;
}
