package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知接收目标载体（通知公告四端拆分后仅作内存载体，无对应表）：
 * 原 notice_target 表已随四端拆分移除——接收目标物化为三端收件箱副本
 * （dept_notice/street_notice/citizen_notice），本类用于：
 *   · 发布请求的目标集合校验与传递（buildTargets）
 *   · 管理端列表/详情的接收对象重构展示（targetsOf，从三收件箱反查）
 * 字段形状与前端 JSON 契约保持一致（targetType: DEPT/STREET/PUBLIC）。
 */
@Data
@NoArgsConstructor
public class NoticeTarget {

    private Long id;
    /** 通知ID（notice.id） */
    private Long noticeId;
    /** 接收类型：DEPT部门/STREET街道办/PUBLIC市民公开 */
    private String targetType;
    /** 部门code或街道办名（PUBLIC为NULL） */
    private String targetCode;
    private LocalDateTime createTime;
}
