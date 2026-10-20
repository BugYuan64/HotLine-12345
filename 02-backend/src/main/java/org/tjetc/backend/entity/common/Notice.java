package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端公告主表 admin_notice（通知公告四端拆分：管理端为唯一发布落点，全量权威）
 * - status=1 展示中 / 0 已下架；仅查询 status=1 按置顶+发布时间倒序展示
 * - 发布权限矩阵（高权力→低权力）：ADMIN→部门/街道办/市民公开；DEPT→街道办/市民公开；STREET→市民公开
 * - 发布/编辑/下架/重发/删除后按接收目标扇出快照至 dept_notice/street_notice/citizen_notice 三端收件箱
 * - 原 notice_target（接收对象）/ notice_read（逐条已读）两表已随拆分移除：
 *   接收对象 = 三收件箱中存在的副本（可重构），已读 = 收件箱行上的 is_read
 */
@Data
@NoArgsConstructor
public class Notice {

    private Long id;
    /** 公告标题 */
    private String title;
    /** 公告正文（富文本/纯文本均可，前端按纯文本展示） */
    private String content;
    /** 公告摘要（用于列表卡片展示，可选） */
    private String summary;
    /** 发布人姓名（脱敏/可空） */
    private String publisher;
    /** 发布者类型：ADMIN管理员/DEPT部门 */
    private String publisherType;
    /** 发布部门code（DEPT时填，如URBAN，与sys_user.user_type同源） */
    private String publisherDept;
    /** 是否置顶：1 置顶 / 0 普通 */
    private Integer topFlag;
    /** 状态：1 展示中 / 0 已下架 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
