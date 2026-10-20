package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 市民端公开通知 citizen_notice（通知公告四端拆分）
 * 管理端/部门端/街道办端发布"市民公开"通知后扇出的市民端副本（一通知一行）。
 * 市民端只接收不发布（层级权限：STREET→PUBLIC 为最低一级），无已读跟踪。
 */
@Data
@NoArgsConstructor
public class CitizenNotice {

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
    private LocalDateTime createTime;
}
