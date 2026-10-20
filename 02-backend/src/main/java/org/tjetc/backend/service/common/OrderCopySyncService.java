package org.tjetc.backend.service.common;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.entity.common.WorkOrder;
import org.tjetc.backend.mapper.dept.DeptWorkOrderMapper;
import org.tjetc.backend.mapper.street.StreetWorkOrderMapper;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;

/**
 * 工单三端副本同步守卫（数据表解耦的核心编排点）
 *
 * 【副本不变式】
 *   dept_work_order   有行 ⟺ 管理端工单 department 非空 且 status != PENDING_DISPATCH
 *   street_work_order 有行 ⟺ 管理端工单 street_office 非空 且状态未回退至管理端/部门待分派池
 *
 * 同步策略（同一数据库事务内，由调用方的 @Transactional 保证原子性）：
 *   1. 管理端权威副本：实体全字段 UPDATE（OrderService 各流转方法先查后改的对象）
 *   2. 部门/街道副本：按不变式「先物理删除同 id 旧副本，再从 admin_work_order 整行 SQL 拷贝」，
 *      保证逐字段镜像（含实体未映射列），杜绝字段遗漏与端间漂移
 *
 * 各端读写边界：
 *   管理端/市民/系统 → admin_work_order；部门端 → dept_work_order；街道办端 → street_work_order。
 *   副本缺失即工单未到达该端（requireDeptOrder/requireStreetOrder 直接判空拦截）。
 */
@Service
@RequiredArgsConstructor
public class OrderCopySyncService {

    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final DeptWorkOrderMapper deptWorkOrderMapper;
    private final StreetWorkOrderMapper streetWorkOrderMapper;

    /** 判断工单当前是否应存在部门端副本：已确定分派部门且未回退管理端待分派池 */
    public static boolean hasDeptCopy(WorkOrder order) {
        return order.getDepartment() != null && !order.getDepartment().isBlank()
                && !"PENDING_DISPATCH".equals(order.getStatus());
    }

    /** 判断工单当前是否应存在街道办端副本：已确定承办街道办且未回退管理端/部门待分派池 */
    public static boolean hasStreetCopy(WorkOrder order) {
        if (order.getStreetOffice() == null || order.getStreetOffice().isBlank()) {
            return false;
        }
        return switch (order.getStatus() == null ? "" : order.getStatus()) {
            case "PENDING_DISPATCH", "PENDING_REASSIGN", "PENDING_DEPT_PROCESS", "PENDING_DEPT_ASSIGN" -> false;
            default -> true;
        };
    }

    /**
     * 全量同步：更新管理端权威副本 + 按不变式刷新部门/街道副本（先删后拷）。
     * 必须在调用方事务内执行（Propagation.REQUIRED 加入外层事务）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void sync(WorkOrder order) {
        // 完成标识默认值兜底：is_completed 列 NOT NULL，新建/存量未归档工单实体值为 null 时
        // 全字段 UPDATE 会撞约束（Column 'is_completed' cannot be null），统一置 0（未完成）
        if (order.getIsCompleted() == null) {
            order.setIsCompleted(0);
        }
        // 1) 管理端权威副本（实体全字段更新，服务层先查后改）
        adminWorkOrderMapper.update(order);
        // 2) 部门端副本：按不变式刷新（先删后拷，SQL 级整行镜像）
        if (hasDeptCopy(order)) {
            deptWorkOrderMapper.deleteById(order.getId());
            deptWorkOrderMapper.copyFromAdmin(order.getId());
        } else {
            deptWorkOrderMapper.deleteById(order.getId());
        }
        // 3) 街道办端副本：按不变式刷新
        if (hasStreetCopy(order)) {
            streetWorkOrderMapper.deleteById(order.getId());
            streetWorkOrderMapper.copyFromAdmin(order.getId());
        } else {
            streetWorkOrderMapper.deleteById(order.getId());
        }
    }

    /** 部门端副本是否存在（流转日志路由用：副本存在才写部门端日志） */
    public boolean deptCopyExists(Long orderId) {
        return deptWorkOrderMapper.selectById(orderId) != null;
    }

    /** 街道办端副本是否存在（流转日志路由用） */
    public boolean streetCopyExists(Long orderId) {
        return streetWorkOrderMapper.selectById(orderId) != null;
    }

    /** 物理删除两端副本（管理端删除工单时调用；流转日志保留可追溯） */
    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteCopies(Long orderId) {
        deptWorkOrderMapper.deleteById(orderId);
        streetWorkOrderMapper.deleteById(orderId);
    }
}
