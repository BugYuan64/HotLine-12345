package org.tjetc.backend.controller.common;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.service.common.UnreadService;

import java.util.Map;

/**
 * 侧边菜单未读红点接口（/api/v1/unread/**，三端共用，按登录会话 user_type 自动区分所属端）。
 * summary 返回当前用户所属端的各模块未读数；mark-read 上报某模块已读清零。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/unread")
public class UnreadController {

    private final UnreadService unreadService;

    /** 当前用户各模块未读数汇总 */
    @GetMapping("/summary")
    public Result<Map<String, Long>> summary() {
        return Result.ok(unreadService.summary(UserContext.get()));
    }

    /** 查询某模块当前游标（前端快照判新用）：返回 LocalDateTime 或 null */
    @GetMapping("/last-viewed")
    public Result<String> lastViewed(@RequestParam String module) {
        var t = unreadService.lastViewed(UserContext.get(), module);
        return Result.ok(t == null ? null : t.toString());
    }

    /** 上报某模块已读（进入该模块页面时调用）：body { "module": "appeal_parent" } */
    @PostMapping("/mark-read")
    public Result<Void> markRead(@RequestBody Map<String, String> body) {
        unreadService.markRead(UserContext.get(), body == null ? null : body.get("module"));
        return Result.ok(null);
    }
}