package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.tjetc.backend.entity.common.SysMessage;
import org.tjetc.backend.entity.common.WorkOrder;
import org.tjetc.backend.mapper.common.SysMessageMapper;
import org.tjetc.backend.mapper.common.SysUserMapper;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;
import org.tjetc.backend.mapper.admin.AdminOrderArchiveMapper;
import org.tjetc.backend.service.common.OrderCopySyncService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单时效管理定时任务（需求 25(1) 时效要求 / API 11.1.1 超时机制）：
 * - 超期标记：截止时间已过自动标记 is_overdue=1（状态不变，列表与看板高亮）
 * - 自动归档：办结后7天自动归档（API 11.3.6）
 * - 接收超时提醒：街道办超24h未接收自动站内提醒、超48h自动预警通知街道办负责人与职能部门
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutService {

    /** 接收提醒去重键（24h/48h 各提醒一次） */
    private static final String REMIND_KEY_PREFIX = "order:remind:";

    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final OrderCopySyncService orderCopySyncService;
    private final AdminOrderArchiveMapper adminOrderArchiveMapper;
    private final OrderLogService orderLogService;
    private final SysMessageMapper messageMapper;
    private final SysUserMapper sysUserMapper;
    private final StringRedisTemplate redis;

    /**
     * 超期标记 + 接收超时提醒：每小时整点执行（cron 可配置）
     */
    @Scheduled(cron = "${app.order.timeout-cron:0 0 * * * ?}")
    public void scheduledTimeoutScan() {
        // 执行scheduledTimeoutScan操作
        markOverdue();
        receiveReminders();
    }

    /**
     * 自动归档：每日 02:10 执行（cron 可配置）
     */
    @Scheduled(cron = "${app.order.archive-cron:0 10 2 * * ?}")
    public void scheduledAutoArchive() {
        // 执行scheduledAutoArchive操作
        autoArchive();
    }

    /** 超期标记（需求 21(6)：超时自动标记，状态不变；三端解耦：同步刷新部门/街道副本） */
    @Transactional
    public int markOverdue() {
        // 标记Overdue
        List<WorkOrder> candidates = adminWorkOrderMapper.selectOverdueCandidates();
        int marked = 0;
        for (WorkOrder order : candidates) {
            try {
                order.setIsOverdue(1);
                orderCopySyncService.sync(order);
                orderLogService.record(order.getId(), "OVERDUE", null, "SYSTEM",
                        "处理超时，自动标记超期", null, null);
                marked++;
            } catch (Exception e) {
                log.warn("工单 {} 超期标记失败：{}", order.getId(), e.getMessage());
            }
        }
        if (marked > 0) {
            log.warn("超期标记完成：{} 单", marked);
        }
        return marked;
    }

    /** 办结后7天自动归档（API 11.3.6；三端解耦：归档标记同步至各端副本） */
    @Transactional
    public int autoArchive() {
        // 执行autoArchive操作
        List<WorkOrder> candidates = adminWorkOrderMapper.selectAutoArchiveCandidates();
        int archived = 0;
        for (WorkOrder order : candidates) {
            try {
                order.setIsArchived(1);
                order.setArchiveTime(LocalDateTime.now());
                order.setArchiveType("AUTO");
                // 完成标识：AUTO 归档候选均为办结态（DEPT_AUDITED/CLOSED/EVALUATED），成功处理=1
                // （与 finalConfirm 归档路径一致，避免归档页"是否完成"显示空）
                order.setIsCompleted(1);
                orderCopySyncService.sync(order);
                // 终审归档独立表：整行快照写入 admin_order_archive
                adminOrderArchiveMapper.deleteById(order.getId());
                adminOrderArchiveMapper.copyFromAdmin(order.getId());
                orderLogService.record(order.getId(), "ARCHIVE", null, "SYSTEM", "办结满7天自动归档", null, null);
                archived++;
            } catch (Exception e) {
                log.warn("工单 {} 自动归档失败：{}", order.getId(), e.getMessage());
            }
        }
        if (archived > 0) {
            log.info("自动归档完成：{} 单", archived);
        }
        return archived;
    }

    /** 街道办接收超时提醒（API 11.1.1：超24h提醒，超48h预警），Redis 去重各提醒一次 */
    public int receiveReminders() {
        // 接单Reminders
        LocalDateTime now = LocalDateTime.now();
        List<WorkOrder> pending = adminWorkOrderMapper.selectPendingReceiveBefore(now.minusHours(24));
        int reminded = 0;
        for (WorkOrder order : pending) {
            try {
                long hours = Duration.between(order.getCreateTime(), now).toHours();
                if (hours >= 48 && setRemindFlag(order.getId(), "48")) {
                    remind(order, "接收超时预警", "工单 " + order.getOrderNo() + " 分派超过48小时未接收，请立即处理，已同步通知职能部门",
                            true);
                    reminded++;
                } else if (hours >= 24 && setRemindFlag(order.getId(), "24")) {
                    remind(order, "接收超时提醒", "工单 " + order.getOrderNo() + " 分派超过24小时未接收，请及时接收处置",
                            false);
                    reminded++;
                }
            } catch (Exception e) {
                log.warn("工单 {} 接收提醒失败：{}", order.getId(), e.getMessage());
            }
        }
        if (reminded > 0) {
            log.info("接收超时提醒完成：{} 单", reminded);
        }
        return reminded;
    }

    private void remind(WorkOrder order, String title, String content, boolean warnDept) {
        // 通知承办街道办人员
        sysUserMapper.selectEnabledByUserType("STREET").stream()
                .filter(u -> order.getStreetOffice() != null && order.getStreetOffice().equals(u.getRegion()))
                .forEach(u -> push(u.getId(), null, title, content));
        // 48h 预警同步通知对应职能部门（需求 21(1)）
        if (warnDept) {
            String userType = switch (order.getCategory() == null ? "" : order.getCategory()) {
                case "URBAN" -> "URBAN";
                case "PROPERTY" -> "PROPERTY";
                case "LIVELIHOOD" -> "LIVELIHOOD";
                default -> null;
            };
            if (userType != null) {
                sysUserMapper.selectEnabledByUserType(userType)
                        .forEach(u -> push(u.getId(), null, title, content));
            }
        }
    }

    private void push(Long userId, String phone, String title, String content) {
        // 推送消息
        SysMessage message = new SysMessage();
        message.setUserId(userId);
        message.setPhone(phone);
        message.setMsgType("ORDER_DISPATCHED");
        message.setTitle(title);
        message.setContent(content);
        message.setIsRead(0);
        message.setChannel("IN_APP");
        messageMapper.insert(message);
    }

    /** Redis 去重：返回 true 表示本次应提醒（键带 TTL，Redis 异常时降级为直接提醒） */
    private boolean setRemindFlag(Long orderId, String stage) {
        try {
            String key = REMIND_KEY_PREFIX + orderId + ":" + stage;
            Boolean ok = redis.opsForValue().setIfAbsent(key, "1", Duration.ofDays(2));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // Redis 不可用时降级为直接提醒（可能重复提醒，但不遗漏）
            return true;
        }
    }
}