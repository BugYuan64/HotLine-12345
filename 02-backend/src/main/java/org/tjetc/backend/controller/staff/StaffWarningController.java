package org.tjetc.backend.controller.staff;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.staff.WarningDispatchRequest;
import org.tjetc.backend.service.common.UnreadService;
import org.tjetc.backend.service.staff.StaffWarningService;
import org.tjetc.backend.service.staff.WarningDispatchService;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 部门端局部预警接口（需求 P1）：
 * 仅查询归属本部门的已发送预警记录 + 标记响应，数据范围按 JWT 中 user_type 自动过滤（API 2.5）
 * 另提供预警分发到街道办能力：可分发街道候选 / 执行分发 / 分发进度（与方案「预警分发」配套）
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/staff")
public class StaffWarningController {

    private final StaffWarningService staffWarningService;
    private final WarningDispatchService warningDispatchService;
    private final UnreadService unreadService;

    /** 本部门预警记录分页（只读）：一级分类由登录部门自动确定，支持二级分类/等级/辖区/触发时间筛选 */
    @GetMapping("/warnings")
    public Result<Map<String, Object>> warnings(
            @RequestParam(required = false) String sub_category,
            @RequestParam(required = false) String warning_level,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 本部门预警分页
        PageData<Map<String, Object>> data = staffWarningService.page(
                sub_category, warning_level, region, start_time, end_time, page, pageSize, UserContext.get());
        // 部门端预警按"发送给部门时间"判新（口径同未读计数）
        unreadService.decorateNew(UserContext.get(), UnreadService.MODULE_WARNING, data, "dept_sent_time");
        return Result.page(data);
    }

    /** 二级分类建议列表（部门端下拉筛选数据源）：按一级分类返回已用于工单的二级分类 */
    @GetMapping("/warnings/sub-categories")
    public Result<List<String>> subCategories(@RequestParam(required = false) String category) {
        // 二级分类建议
        return Result.ok(staffWarningService.listSubCategories(category));
    }

    /** 标记本部门预警已响应（归属校验：仅本部门记录可操作） */
    @PutMapping("/warnings/{id}/respond")
    public Result<Map<String, Object>> respond(@PathVariable Long id) {
        // 标记已响应
        return Result.ok(staffWarningService.respond(id, UserContext.get()));
    }

    /** 工作台待跟进预警数（已发送给本部门且响应闭环未完成） */
    @GetMapping("/warnings/unresponded-count")
    public Result<Map<String, Object>> unrespondedCount() {
        // 待跟进预警计数
        return Result.ok(Map.of("unresponded_count",
                staffWarningService.unrespondedCount(UserContext.get())));
    }

    /** 可分发街道候选（按区县分组；辖区为空列全市） */
    @GetMapping("/warnings/{id}/streets")
    public Result<List<Map<String, Object>>> dispatchStreets(@PathVariable Long id) {
        // 可分发街道候选
        return Result.ok(warningDispatchService.candidates(id, UserContext.get()));
    }

    /** 分发预警到选中的街道办 */
    @PostMapping("/warnings/{id}/dispatch")
    public Result<Map<String, Object>> dispatch(@PathVariable Long id,
                                                @Valid @RequestBody WarningDispatchRequest request) {
        // 执行分发
        return Result.ok(warningDispatchService.dispatch(id, request, UserContext.get()));
    }

    /** 本部门查看预警分发进度（只读） */
    @GetMapping("/warnings/{id}/dispatches")
    public Result<List<Map<String, Object>>> dispatches(@PathVariable Long id) {
        // 分发进度
        return Result.ok(warningDispatchService.staffProgress(id, UserContext.get()));
    }
}