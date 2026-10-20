package org.tjetc.backend.controller.street;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.service.common.UnreadService;
import org.tjetc.backend.service.staff.StaffWarningService;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 街道办端局部预警接口（/api/v1/street/**，独立于部门端）：
 * 仅街道办（STREET）账号可访问，查看本辖区已发送预警 + 标记响应。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/street")
public class StreetWarningController {

    private final StaffWarningService staffWarningService;
    private final UnreadService unreadService;

    /** 街道办预警记录分页（本辖区，只读） */
    @GetMapping("/warnings")
    public Result<Map<String, Object>> warnings(
            @RequestParam(required = false) String sub_category,
            @RequestParam(required = false) String warning_level,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        PageData<Map<String, Object>> data = staffWarningService.page(
                sub_category, warning_level, region, start_time, end_time, page, pageSize, UserContext.get());
        // 街道办端预警按"分发时间"判新（口径同未读计数）
        unreadService.decorateNew(UserContext.get(), UnreadService.MODULE_WARNING, data, "dispatch_time");
        return Result.page(data);
    }

    /** 标记本街道办预警已响应 */
    @PutMapping("/warnings/{id}/respond")
    public Result<Map<String, Object>> respond(@PathVariable Long id) {
        return Result.ok(staffWarningService.respond(id, UserContext.get()));
    }

    /** 工作台待响应预警数（分发给本街道且未响应） */
    @GetMapping("/warnings/unresponded-count")
    public Result<Map<String, Object>> unrespondedCount() {
        return Result.ok(Map.of("unresponded_count",
                staffWarningService.unrespondedCount(UserContext.get())));
    }
}
