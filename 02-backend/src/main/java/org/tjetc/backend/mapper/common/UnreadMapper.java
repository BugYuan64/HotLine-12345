package org.tjetc.backend.mapper.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 模块未读游标与未读计数 Mapper（三端侧边菜单未读红点）：
 * - 游标表 sys_module_read 记录「用户 × 模块」最近查看时间
 * - 未读数 = 各业务表在权限范围内 create_time/trigger_time 大于游标时间的新增记录数
 * 本 Mapper 仅新增，不改动任何既有业务 Mapper。
 */
@Mapper
public interface UnreadMapper {

    // ============ 游标读写（sys_module_read） ============

    /** 查询某用户某模块的最近查看时间（游标）；首次访问无记录返回 null */
    LocalDateTime selectLastViewed(@Param("userId") Long userId, @Param("module") String module);

    /** 上报/更新游标为指定时间（首次插入，重复则刷新，依赖 uk_user_module）；
     *  游标必须与应用侧业务时间（apply_time/create_time 等）同源，
     *  不能用 DB NOW()，否则与 MySQL 服务器时钟不一致会导致红点无法清零 */
    int upsertLastViewed(@Param("userId") Long userId, @Param("module") String module,
                         @Param("lastViewedAt") LocalDateTime lastViewedAt);

    // ============ 未读计数（时间 + 权限范围过滤） ============

    /** 父诉求新增（管理端全量） */
    long countAppealParentNew(@Param("after") LocalDateTime after);

    /** 子诉求新增（管理端全量） */
    long countAppealChildNew(@Param("after") LocalDateTime after);

    /** 工单新增（管理端全量，is_deleted=0） */
    long countWorkOrderNew(@Param("after") LocalDateTime after);

    /** 工单待分配数（管理端实时待办）：PENDING_DISPATCH 未删除工单数，不按游标过滤 */
    long countWorkOrderUnassigned();

    /** 工单新增（部门端：本分类 category + 本区县 county_code） */
    long countWorkOrderNewByCategory(@Param("after") LocalDateTime after, @Param("category") String category,
                                     @Param("countyCode") String countyCode);

    /** 工单新增（街道办端：本辖区 street_office） */
    long countWorkOrderNewByStreet(@Param("after") LocalDateTime after, @Param("streetOffice") String streetOffice);

    /** 预警记录新增（管理端全量） */
    long countWarningNew(@Param("after") LocalDateTime after);

    /** 预警记录新增（部门端：已发送且归属本部门，department 含本分类code或「区县:分类」复合键） */
    long countWarningNewByCategory(@Param("after") LocalDateTime after, @Param("category") String category,
                                   @Param("countyCode") String countyCode);

    /** 预警记录新增（街道办端：已发送且分发给本街道） */
    long countWarningNewByStreet(@Param("after") LocalDateTime after, @Param("streetOffice") String streetOffice);

    /** 敏感事件新增（管理端全量） */
    long countSensitiveEventNew(@Param("after") LocalDateTime after);

    /** 系统审计新增（管理端：排除「收到工单」） */
    long countAuditSysNew(@Param("after") LocalDateTime after);

    /** 部门审计新增（管理端：部门账号操作 + 归属部门的收到工单） */
    long countAuditDeptNew(@Param("after") LocalDateTime after);

    /** 街道办审计新增（管理端：街道办账号操作 + 归属街道办的收到工单） */
    long countAuditStreetNew(@Param("after") LocalDateTime after);

    /** 系统审计新增（部门端：本区县本部门账号操作 + 归属本区县本部门的收到工单） */
    long countAuditNewByDept(@Param("after") LocalDateTime after, @Param("dept") String dept,
                             @Param("countyCode") String countyCode);

    /** 系统审计新增（街道办端：本账号操作 + 归属本街道的收到工单） */
    long countAuditNewByStreet(@Param("after") LocalDateTime after,
                               @Param("operator") String operator,
                               @Param("targetType") String targetType);

    /** 账号审核新增（管理端：新提交的审核申请，按申请时间） */
    long countAccountAuditNew(@Param("after") LocalDateTime after);

    /** 终审归档新增（管理端：新归档的工单，按归档更新时间） */
    long countFinalAuditNew(@Param("after") LocalDateTime after);
}