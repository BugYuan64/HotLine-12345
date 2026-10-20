package org.tjetc.backend.controller.citizen;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.citizen.NotificationSettingsRequest;
import org.tjetc.backend.service.citizen.MessageService;

import java.util.Map;

/**
 * 公众端消息通知（API P-N-01~03）：
 * 消息列表 / 标记已读 / 通知偏好设置
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/public/messages")
public class PublicMessageController {

    private final MessageService messageService;

    /** 消息列表（API P-N-01） */
    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        // 查询列表数据
        return Result.ok(messageService.list(UserContext.get(), page, pageSize));
    }

    /** 未读数（API P-N-01） */
    @GetMapping("/unread-count")
    public Result<Map<String, Object>> unreadCount() {
        // 获取未读数量
        return Result.ok(messageService.unreadCount(UserContext.get()));
    }

    /** 标记单条已读（API P-N-02） */
    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        // 标记为已读
        messageService.markRead(UserContext.get(), id);
        return Result.ok(null);
    }

    /** 全部已读（API P-N-02） */
    @PostMapping("/read-all")
    public Result<Void> markAllRead() {
        // 全部标记已读
        messageService.markAllRead(UserContext.get());
        return Result.ok(null);
    }

    /** 查询通知设置（API P-N-03） */
    @GetMapping("/settings")
    public Result<Map<String, Object>> getSettings() {
        return Result.ok(messageService.getSettings(UserContext.get()));
    }

    /** 更新通知设置（API P-N-03） */
    @PutMapping("/settings")
    public Result<Void> updateSettings(@Valid @RequestBody NotificationSettingsRequest request) {
        // 更新配置信息
        messageService.updateSettings(UserContext.get(), request);
        return Result.ok(null);
    }
}
