package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.tjetc.backend.mapper.admin.SensitiveEventMapper;
import org.tjetc.backend.mapper.admin.WarningRecordMapper;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端首页"待我介入"待办聚合（对应 GET /api/v1/admin/todo/summary）：
 * 汇总散落在工单/预警/敏感事件各列表中的待管理端处理事项，
 * 全部复用既有 Mapper 计数方法，只做聚合不落新表。
 */
@Service
@RequiredArgsConstructor
public class TodoSummaryService {

    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final WarningRecordMapper warningRecordMapper;
    private final SensitiveEventMapper sensitiveEventMapper;

    public Map<String, Object> summary() {
        Map<String, Object> data = new LinkedHashMap<>();
        // 部门申请驳回/退回后待重新分派的工单（部门端「申请驳回」deptApplyReject 置 PENDING_REASSIGN）
        data.put("reassign_orders", adminWorkOrderMapper.countTodoByStatus(OrderService.STATUS_PENDING_REASSIGN));
        // 待管理端分派工单（工单初始状态 PENDING_DISPATCH，管理端需分派给部门）
        data.put("pending_assign", adminWorkOrderMapper.countTodoByStatus(OrderService.STATUS_PENDING_DISPATCH));
        // 待全局终审工单（新流程：部门审核通过后 DEPT_AUDITED，待管理端终审确认）
        data.put("final_audit", adminWorkOrderMapper.countTodoByStatus(OrderService.STATUS_DEPT_AUDITED));
        // 待审核预警
        data.put("warning_review", warningRecordMapper.countPage(null, null, null, "PENDING", null, null));
        // 紧急预警超时未响应（口径与升级扫描一致：SENT 且 24 小时未响应）
        data.put("warning_urgent",
                warningRecordMapper.countUnhandledUrgentOlderThan(LocalDateTime.now().minusHours(24)));
        // 未处理敏感事件
        data.put("sensitive_events", sensitiveEventMapper.countPage(null, "UNHANDLED"));
        return data;
    }
}