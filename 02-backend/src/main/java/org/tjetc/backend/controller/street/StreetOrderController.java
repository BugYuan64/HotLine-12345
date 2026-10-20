package org.tjetc.backend.controller.street;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.staff.ApplyDelayRequest;
import org.tjetc.backend.dto.staff.ApplyReturnRequest;
import org.tjetc.backend.dto.staff.DisposeRequest;
import org.tjetc.backend.service.admin.FileService;
import org.tjetc.backend.service.admin.OrderService;
import org.tjetc.backend.service.common.UnreadService;
import org.tjetc.backend.service.admin.OrderLogService;
import org.tjetc.backend.service.street.StreetOrderService;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 街道办端工单接口（/api/v1/street/**，独立于部门端 /api/v1/staff/**）：
 * 仅街道办（STREET）账号可访问，按辖区（region）自动过滤数据范围。
 * 处理/退回/延期/驳回/附件/时间线/工作台统计。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/street")
public class StreetOrderController {

    private final OrderService orderService;
    private final StreetOrderService streetOrderService;
    private final OrderLogService orderLogService;
    private final FileService fileService;
    private final UnreadService unreadService;

    /** 街道办工作台首页统计（本辖区） */
    @GetMapping("/workbench")
    public Result<Map<String, Object>> workbench() {
        return Result.ok(streetOrderService.workbenchSummary(UserContext.get()));
    }

    /** 街道办权限内工单查询（本辖区） */
    @GetMapping("/orders")
    public Result<Map<String, Object>> orders(@RequestParam(required = false) String order_no,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                              @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        PageData<Map<String, Object>> data = streetOrderService.page(
                order_no, keyword, status, start_time, end_time, page, pageSize, UserContext.get());
        // 街道办端工单按"到达街道办时间"判新（口径同未读计数）
        return Result.page(data);
    }

    /** 街道办工单详情 */
    @GetMapping("/orders/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(streetOrderService.detail(id, UserContext.get()));
    }

    /** 街道办处理工单（待街道办处理 → 街道办已处理） */
    @PostMapping("/orders/{id}/dispose")
    public Result<Map<String, Object>> dispose(@PathVariable Long id, @Valid @RequestBody DisposeRequest request) {
        return Result.ok(orderService.dispose(id, request, UserContext.get()));
    }

    /** 街道办退回申请（待街道办处理 → 待分派） */
    @PostMapping("/orders/{id}/apply-return")
    public Result<Map<String, Object>> applyReturn(@PathVariable Long id,
                                                   @Valid @RequestBody ApplyReturnRequest request) {
        return Result.ok(orderService.applyReturn(id, request.returnReason(), UserContext.get()));
    }

    /** 街道办延期申请（最多2次，每次≤3个工作日） */
    @PostMapping("/orders/{id}/apply-delay")
    public Result<Map<String, Object>> applyDelay(@PathVariable Long id,
                                                  @Valid @RequestBody ApplyDelayRequest request) {
        return Result.ok(orderService.applyDelay(id, request.delayReason(), request.expectedTime(), UserContext.get()));
    }

    /** 街道办驳回（待街道办处理 → 街道办已驳回） */
    @PostMapping("/orders/{id}/reject")
    public Result<Map<String, Object>> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reject_reason");
        String remark = body == null ? null : body.get("reject_remark");
        return Result.ok(orderService.rejectOrder(id, reason, remark, UserContext.get()));
    }

    /** 处置凭证附件上传 */
    @PostMapping("/orders/{id}/attachment")
    public Result<Map<String, Object>> uploadAttachment(@PathVariable Long id,
                                                        @RequestParam("file") MultipartFile file,
                                                        @RequestParam("attachment_type") String attachmentType) {
        LoginUser user = UserContext.get();
        streetOrderService.requireScope(id, user);
        return Result.ok(fileService.uploadDisposeAttachment(file, attachmentType, id, user.userId()));
    }

    /** 工单流转时间线 */
    @GetMapping("/orders/{id}/timeline")
    public Result<Map<String, Object>> timeline(@PathVariable Long id) {
        streetOrderService.requireScope(id, UserContext.get());
        return Result.ok(Map.of("timeline", orderLogService.timeline(id, OrderLogService.TimelineEnd.STREET)));
    }
}
