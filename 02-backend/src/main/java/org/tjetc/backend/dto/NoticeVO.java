package org.tjetc.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.tjetc.backend.entity.common.NoticeTarget;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知公告列表/详情项（通知公告模块，管理端/部门端/街道办端共用）
 * 在 notice 基础字段上扩展：接收对象摘要（列表展示）+ 逐条已读标记（部门/街道办端）
 */
@Data
@NoArgsConstructor
public class NoticeVO {

    private Long id;
    /** 通知标题 */
    private String title;
    /** 通知正文 */
    private String content;
    /** 摘要（列表卡片展示用） */
    private String summary;
    /** 发布人姓名（脱敏） */
    private String publisher;
    /** 发布者类型：ADMIN管理员/DEPT部门 */
    private String publisherType;
    /** 发布部门code（DEPT时填） */
    private String publisherDept;
    /** 发布部门中文名（列表直接展示） */
    private String publisherDeptName;
    /** 是否置顶：1 置顶 / 0 普通 */
    private Integer topFlag;
    /** 状态：1 展示中 / 0 已下架 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 接收对象摘要（列表展示，如"城管部门、全部街道办、市民公开"） */
    private String targetSummary;
    /** 是否已读：0未读/1已读（部门/街道办端收到列表用；管理端恒为1） */
    private Integer isRead;
    /** 接收对象明细（详情/编辑回填用） */
    private List<NoticeTarget> targets;
}
