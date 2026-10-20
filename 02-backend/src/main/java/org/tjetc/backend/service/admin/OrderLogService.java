package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.entity.common.OrderLog;
import org.tjetc.backend.mapper.admin.AdminOrderLogMapper;
import org.tjetc.backend.mapper.dept.DeptOrderLogMapper;
import org.tjetc.backend.mapper.street.StreetOrderLogMapper;
import org.tjetc.backend.service.common.OrderCopySyncService;
import tools.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单流转日志服务（API 11.1.5 时间线 / 需求 25(4) 状态机规则）
 * 【状态机闭环】所有状态变更必须写入 order_log，禁止绕过
 *
 * 三端数据解耦后的日志路由（与副本不变式对齐）：
 *   admin_order_log 恒写入（全量）；工单已分派至部门（dept 副本存在）时同步写 dept_order_log；
 *   工单已分派至街道办（street 副本存在）时同步写 street_order_log。
 *   各端时间线只读本端日志表，内容与拆分前完全一致。
 */
@Service
@RequiredArgsConstructor
public class OrderLogService {

    /** 操作类型 → 时间线节点文案（API 11.1.5 示例节点名） */
    private static final Map<String, String> NODE_LABELS = Map.ofEntries(
            Map.entry("GENERATE", "工单分类完成"),
            Map.entry("CONFIRM", "部门接收"),
            Map.entry("UPGRADE", "等级升级"),
            Map.entry("DISPATCH", "管理员分派"),
            Map.entry("AUTO_DISPATCH", "自动分派"),
            Map.entry("VERIFY", "部门核验"),
            Map.entry("ASSIGN_STREET", "分派街道办"),
            Map.entry("RECEIVE", "街道办接收"),
            Map.entry("DISPOSE", "提交处置结果"),
            Map.entry("RETURN", "退回"),
            Map.entry("DELAY", "延期"),
            Map.entry("AUDIT", "部门审核"),
            Map.entry("SCORE", "处置评分"),
            Map.entry("FINAL_AUDIT", "终审"),
            Map.entry("ARCHIVE", "归档"),
            Map.entry("REOPEN", "重开"),
            Map.entry("EVALUATE", "市民评价"),
            Map.entry("EDIT", "编辑工单"),
            Map.entry("DELETE", "删除工单"),
            Map.entry("CLASSIFY_CORRECT", "分类人工修正"),
            // 补充缺失映射（避免时间线显示英文原始码）
            Map.entry("DEPT_CONFIRM", "部门确认"),
            Map.entry("CANCEL_CONFIRM", "取消确认"),
            Map.entry("DEPT_APPLY_REJECT", "部门申请驳回"),
            Map.entry("STREET_REJECT", "街道办驳回"),
            Map.entry("DEPT_REJECT", "部门同意驳回"),
            Map.entry("DEPT_REASSIGN", "部门重新分派"),
            Map.entry("REASSIGN", "管理端重新分派"),
            Map.entry("RECLASSIFY", "重新分类"),
            Map.entry("MERGE", "工单合并"),
            Map.entry("DEMERGE", "取消合并"),
            Map.entry("DOWNGRADE", "等级降级"),
            Map.entry("OVERDUE", "超期标记"),
            Map.entry("MERGE_UPGRADE", "等级升级"),
            Map.entry("MERGE_DOWNGRADE", "等级降级"));

    /** 时间线过滤：工单合并相关（含合并触发的等级升降）与超期噪声不展示，只保留主流程 */
    private static final Set<String> FILTERED_TYPES = Set.of(
            "MERGE", "DEMERGE", "MERGE_UPGRADE", "MERGE_DOWNGRADE", "OVERDUE");

    /** 操作角色 → 展示文案（时间线角色化展示，不暴露具体人员姓名，API 11.1.5）。
     *  与 8 大部门代号对齐；保留 PROPERTY/LIVELIHOOD 兼容旧 3 类时期的历史日志 */
    private static final Map<String, String> ROLE_LABELS = Map.ofEntries(
            Map.entry("CITIZEN", "市民"), Map.entry("SYSTEM", "系统"), Map.entry("ADMIN", "系统管理员"),
            Map.entry("STREET", "街道办"),
            Map.entry("URBAN", "城管部门"), Map.entry("TRAFFIC", "交通部门"),
            Map.entry("MARKET", "市场监管部门"), Map.entry("ENVIRONMENT", "生态环境部门"),
            Map.entry("HOUSING", "住建部门"), Map.entry("LABOR", "人社部门"),
            Map.entry("PUBLIC", "市政部门"), Map.entry("HEALTH", "卫健部门"),
            Map.entry("PROPERTY", "物业部门"), Map.entry("LIVELIHOOD", "民生部门"));

    private final AdminOrderLogMapper adminOrderLogMapper;
    private final DeptOrderLogMapper deptOrderLogMapper;
    private final StreetOrderLogMapper streetOrderLogMapper;
    private final OrderCopySyncService orderCopySyncService;
    private final ObjectMapper objectMapper;

    /**
     * 记录流转日志（修改前后数据 JSON 留痕）：
     * 管理端全量写入；按副本不变式判定副本存在时同步写部门端/街道办端日志表。
     * 注意：必须在流转方法完成 sync 之后调用（副本存在性反映流转后的最新状态）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Long orderId, String operateType, Long operatorId, String operatorRole,
                       String content, Object before, Object after) {
        OrderLog log = build(orderId, operateType, operatorId, operatorRole, content, before, after);
        adminOrderLogMapper.insert(log);
        // 副本存在才写对应端日志（工单已分派至部门/街道办）
        if (orderCopySyncService.deptCopyExists(orderId)) {
            deptOrderLogMapper.insert(build(orderId, operateType, operatorId, operatorRole, content, before, after));
        }
        if (orderCopySyncService.streetCopyExists(orderId)) {
            streetOrderLogMapper.insert(build(orderId, operateType, operatorId, operatorRole, content, before, after));
        }
    }

    private OrderLog build(Long orderId, String operateType, Long operatorId, String operatorRole,
                           String content, Object before, Object after) {
        OrderLog log = new OrderLog();
        log.setOrderId(orderId);
        log.setOperateType(operateType);
        log.setOperatorId(operatorId);
        log.setOperatorRole(operatorRole);
        log.setOperateContent(content);
        log.setBeforeData(toJson(before));
        log.setAfterData(toJson(after));
        return log;
    }

    /**
     * 工单流转时间线（API 11.1.5/11.4.4）：[{node, time, role, content}]，按时间正序。
     * 默认读管理端全量日志（管理端/市民进度用）。
     */
    public List<Map<String, Object>> timeline(Long orderId) {
        return timeline(orderId, TimelineEnd.ADMIN);
    }

    /** 指定端时间线：三端数据解耦后各端只读本端日志表 */
    public List<Map<String, Object>> timeline(Long orderId, TimelineEnd end) {
        List<OrderLog> logs = switch (end) {
            case DEPT -> deptOrderLogMapper.selectByOrderId(orderId);
            case STREET -> streetOrderLogMapper.selectByOrderId(orderId);
            default -> adminOrderLogMapper.selectByOrderId(orderId);
        };
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return logs.stream()
                .filter(l -> !FILTERED_TYPES.contains(l.getOperateType()))
                .map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node", NODE_LABELS.getOrDefault(l.getOperateType(), l.getOperateType()));
            m.put("time", l.getCreateTime() == null ? "" : l.getCreateTime().format(fmt));
            m.put("role", ROLE_LABELS.getOrDefault(l.getOperatorRole(), l.getOperatorRole()));
            m.put("content", l.getOperateContent());
            return m;
        }).toList();
    }

    /** 时间线数据源端标识 */
    public enum TimelineEnd { ADMIN, DEPT, STREET }

    private String toJson(Object obj) {
        // 执行toJson操作
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
