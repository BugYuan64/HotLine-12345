package org.tjetc.backend.controller.street;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.service.common.InboxMessageService;

import java.util.Map;

/**
 * 街道办端消息站接口（/api/v1/street/**，仅 STREET 账号可访问）：
 * 读取按 user_id 推送的本街道办工作人员站内消息（预警分发推送等）。
 * 业务逻辑复用角色中立的 InboxMessageService，与部门端在 Controller 层物理隔离。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/street")
public class StreetMessageController {

    private final InboxMessageService inboxMessageService;

    /** 消息列表 */
    @GetMapping("/messages")
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        // 查询消息列表
        return Result.ok(inboxMessageService.list(UserContext.get().userId(), page, pageSize));
    }

    /** 未读数 */
    @GetMapping("/messages/unread-count")
    public Result<Map<String, Object>> unreadCount() {
        // 查询未读数
        return Result.ok(inboxMessageService.unreadCount(UserContext.get().userId()));
    }

    /** 标记单条已读 */
    @PostMapping("/messages/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        // 标记单条已读
        inboxMessageService.markRead(UserContext.get().userId(), id);
        return Result.ok(null);
    }

    /** 全部已读 */
    @PostMapping("/messages/read-all")
    public Result<Void> markAllRead() {
        // 全部标记已读
        inboxMessageService.markAllRead(UserContext.get().userId());
        return Result.ok(null);
    }
}