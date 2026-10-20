package org.tjetc.backend.service.common;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.mapper.dept.DeptNoticeMapper;
import org.tjetc.backend.mapper.street.StreetNoticeMapper;
import org.tjetc.backend.mapper.common.UnreadMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 侧边菜单未读红点服务（管理端/部门端/街道办端共用，按 user_type 分支）：
 * 未读数 = 各模块在权限范围内、且时间晚于该用户该模块「最近查看游标」的新增记录数。
 * 首次访问无游标时未读为 0（只算上线后的增量）；进入模块时上报游标清零。
 */
@Service
@RequiredArgsConstructor
public class UnreadService {

    private final UnreadMapper unreadMapper;
    private final DeptNoticeMapper deptNoticeMapper;
    private final StreetNoticeMapper streetNoticeMapper;

    // ============ 模块标识 ============
    public static final String MODULE_APPEAL_PARENT = "appeal_parent";
    public static final String MODULE_APPEAL_CHILD = "appeal_child";
    public static final String MODULE_WORK_ORDER = "work_order";
    /** 工单待分配数（管理端实时待办，不随游标清零）：= PENDING_DISPATCH 未删除工单数 */
    public static final String MODULE_WORK_ORDER_UNASSIGNED = "work_order_unassigned";
    public static final String MODULE_WARNING = "warning_record";
    public static final String MODULE_SENSITIVE_EVENT = "sensitive_event";
    public static final String MODULE_AUDIT_SYS = "audit_sys";
    public static final String MODULE_AUDIT_DEPT = "audit_dept";
    public static final String MODULE_AUDIT_STREET = "audit_street";
    public static final String MODULE_ACCOUNT_AUDIT = "account_audit";
    /** 终审归档（独立计数：仅统计新归档的工单） */
    public static final String MODULE_FINAL_AUDIT = "final_audit";
    /**
     * 通知公告（部门端/街道办端）：收件箱已读模式（dept_notice/street_notice 点开详情置已读减一条），不走游标，
     * 因此不在 ALL_MODULES 白名单内（markModuleRead 对其无效），已读仅由通知详情接口驱动。
     */
    public static final String MODULE_NOTICE = "notice";

    /** 三端可上报的模块全集（mark-read 白名单，防任意写入） */
    private static final Set<String> ALL_MODULES = Set.of(
            MODULE_APPEAL_PARENT, MODULE_APPEAL_CHILD, MODULE_WORK_ORDER, MODULE_WORK_ORDER_UNASSIGNED,
            MODULE_WARNING, MODULE_SENSITIVE_EVENT,
            MODULE_AUDIT_SYS, MODULE_AUDIT_DEPT, MODULE_AUDIT_STREET,
            MODULE_ACCOUNT_AUDIT, MODULE_FINAL_AUDIT);

    /**
     * 汇总当前用户所属端的各模块未读数。
     */
    public Map<String, Long> summary(LoginUser user) {
        String userType = user.userType();
        Map<String, Long> result = new LinkedHashMap<>();
        if ("ADMIN".equals(userType)) {
            // 管理端：父诉求/子诉求/工单/预警记录/敏感事件/三类审计/账号审核
            put(result, MODULE_APPEAL_PARENT, countFor(user, MODULE_APPEAL_PARENT, a -> unreadMapper.countAppealParentNew(a)));
            put(result, MODULE_APPEAL_CHILD, countFor(user, MODULE_APPEAL_CHILD, a -> unreadMapper.countAppealChildNew(a)));
            put(result, MODULE_WORK_ORDER, countFor(user, MODULE_WORK_ORDER, a -> unreadMapper.countWorkOrderNew(a)));
            // 工单待分配数：实时待办（PENDING_DISPATCH），不按游标过滤，进入页面也不清零
            put(result, MODULE_WORK_ORDER_UNASSIGNED, unreadMapper.countWorkOrderUnassigned());
            put(result, MODULE_WARNING, countFor(user, MODULE_WARNING, a -> unreadMapper.countWarningNew(a)));
            put(result, MODULE_SENSITIVE_EVENT, countFor(user, MODULE_SENSITIVE_EVENT, a -> unreadMapper.countSensitiveEventNew(a)));
            put(result, MODULE_AUDIT_SYS, countFor(user, MODULE_AUDIT_SYS, a -> unreadMapper.countAuditSysNew(a)));
            put(result, MODULE_AUDIT_DEPT, countFor(user, MODULE_AUDIT_DEPT, a -> unreadMapper.countAuditDeptNew(a)));
            put(result, MODULE_AUDIT_STREET, countFor(user, MODULE_AUDIT_STREET, a -> unreadMapper.countAuditStreetNew(a)));
            put(result, MODULE_ACCOUNT_AUDIT, countFor(user, MODULE_ACCOUNT_AUDIT, a -> unreadMapper.countAccountAuditNew(a)));
            put(result, MODULE_FINAL_AUDIT, countFor(user, MODULE_FINAL_AUDIT, a -> unreadMapper.countFinalAuditNew(a)));
        } else if ("STREET".equals(userType)) {
            // 街道办端：工单/系统审计/局部预警（按本辖区）+ 通知（逐条已读，不走游标）
            String region = user.region();
            put(result, MODULE_WORK_ORDER, countFor(user, MODULE_WORK_ORDER, a -> unreadMapper.countWorkOrderNewByStreet(a, region)));
            String operator = userType + "-" + user.jobNo();
            String targetType = "STREET:" + (region == null ? "" : region);
            put(result, MODULE_AUDIT_SYS, countFor(user, MODULE_AUDIT_SYS, a -> unreadMapper.countAuditNewByStreet(a, operator, targetType)));
            put(result, MODULE_WARNING, countFor(user, MODULE_WARNING, a -> unreadMapper.countWarningNewByStreet(a, region)));
            put(result, MODULE_NOTICE, streetNoticeMapper.countUnread(region));
        } else {
            // 部门端：工单/系统审计/局部预警（按本分类+本区县）+ 通知（逐条已读，不走游标）
            put(result, MODULE_WORK_ORDER, countFor(user, MODULE_WORK_ORDER, a -> unreadMapper.countWorkOrderNewByCategory(a, userType, user.regionCode())));
            put(result, MODULE_AUDIT_SYS, countFor(user, MODULE_AUDIT_SYS, a -> unreadMapper.countAuditNewByDept(a, userType, user.regionCode())));
            put(result, MODULE_WARNING, countFor(user, MODULE_WARNING, a -> unreadMapper.countWarningNewByCategory(a, userType, user.regionCode())));
            put(result, MODULE_NOTICE, deptNoticeMapper.countUnread(userType, user.regionCode()));
        }
        return result;
    }

    /**
     * 查询某模块当前游标（前端快照判新用）：null=首次访问无游标（口径与 countFor 一致：视为无新增）。
     */
    public LocalDateTime lastViewed(LoginUser user, String module) {
        if (module == null || !ALL_MODULES.contains(module)) {
            return null;
        }
        return unreadMapper.selectLastViewed(user.userId(), module);
    }

    /**
     * 上报模块已读：将该模块游标置为当前时间，未读清零。
     */
    @Transactional
    public void markRead(LoginUser user, String module) {
        if (module == null || !ALL_MODULES.contains(module)) {
            return;
        }
        // 游标用应用本地时间（与各业务表 apply_time/create_time 同源），避免与 MySQL 服务器时钟偏差导致红点不清
        unreadMapper.upsertLastViewed(user.userId(), module, LocalDateTime.now());
    }

    /** 计算某模块未读：无游标（首次访问）返回 0；否则按游标时间统计新增 */
    private long countFor(LoginUser user, String module, CountAction action) {
        LocalDateTime last = unreadMapper.selectLastViewed(user.userId(), module);
        if (last == null) {
            return 0L;
        }
        return action.countNew(last);
    }

    /**
     * 行级判新（列表"新"角标用）：记录时间晚于该用户该模块游标即为新增。
     * 口径与 countFor 一致：无游标（首次访问）或时间为空时均返回 false，
     * 保证列表角标数量与侧边栏未读数一致。
     */
    public boolean isNewAfter(Long userId, String module, LocalDateTime time) {
        if (time == null) {
            return false;
        }
        LocalDateTime last = unreadMapper.selectLastViewed(userId, module);
        return last != null && time.isAfter(last);
    }

    /**
     * 列表行批量判新（"新"角标用）：对分页结果每行按 timeKeys 依次取到的
     * 第一个非空 LocalDateTime 判新并写入 is_new；取不到时间字段的行置 false。
     * 与 isNewAfter 口径一致（无游标视为无新增），保证角标数量与未读数一致。
     */
    public void decorateNew(LoginUser user, String module, PageData<Map<String, Object>> data, String... timeKeys) {
        if (user == null || data == null || data.list() == null || data.list().isEmpty()) {
            return;
        }
        LocalDateTime last = unreadMapper.selectLastViewed(user.userId(), module);
        for (Map<String, Object> row : data.list()) {
            LocalDateTime time = null;
            for (String key : timeKeys) {
                time = toLocalDateTime(row.get(key));
                if (time != null) {
                    break;
                }
            }
            row.put("is_new", last != null && time != null && time.isAfter(last));
        }
    }

    /**
     * 兼容多种时间类型：实体对象列表返回 LocalDateTime，裸 Map 列表（如审计日志）
     * 由 MyBatis 默认映射为 java.sql.Timestamp（DATETIME 列），统一转成 LocalDateTime。
     */
    private LocalDateTime toLocalDateTime(Object v) {
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof Date d) {
            return new Timestamp(d.getTime()).toLocalDateTime();
        }
        return null;
    }

    private void put(Map<String, Long> map, String module, long count) {
        map.put(module, count);
    }

    @FunctionalInterface
    private interface CountAction {
        long countNew(LocalDateTime after);
    }
}