package org.tjetc.backend.controller.staff;

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
import org.tjetc.backend.dto.staff.AssignStreetRequest;
import org.tjetc.backend.dto.staff.AuditRejectRequest;
import org.tjetc.backend.dto.staff.BatchAuditRequest;
import org.tjetc.backend.dto.staff.DelayApproveRequest;
import org.tjetc.backend.dto.staff.DisposeRequest;
import org.tjetc.backend.dto.staff.ReceiveRequest;
import org.tjetc.backend.dto.staff.ScoreRequest;
import org.tjetc.backend.dto.staff.VerifyRequest;
import org.tjetc.backend.service.admin.FileService;
import org.tjetc.backend.service.admin.OrderService;
import org.tjetc.backend.service.common.UnreadService;
import org.tjetc.backend.service.admin.OrderLogService;
import org.tjetc.backend.service.dept.DeptOrderService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 部门端工单接口（API 9.2~9.5 / 11.1/11.2 章）：
 * 部门筛选核验、分派至街道办、街道办接收/处置/退回/延期、部门审核/批量审核/评分/延期审批/汇总上报、时间线
 * 数据范围按 JWT 中 user_type + region 自动过滤（API 2.5），越权访问返回 40203
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/staff")
public class StaffOrderController {

    private final OrderService orderService;
    private final DeptOrderService deptOrderService;
    private final OrderLogService orderLogService;
    private final FileService fileService;
    private final UnreadService unreadService;

    /** 部门端确认（待审核 → 待分派） */
    @PostMapping("/orders/{id}/confirm")
    public Result<Map<String, Object>> confirm(@PathVariable Long id) {
        return Result.ok(orderService.confirmOrder(id, UserContext.get()));
    }

    /** 部门筛选核验（API 9.2）：CONFIRM 确认归属 / RETURN 退回重分 */
    @PostMapping("/orders/{id}/verify")
    public Result<Map<String, Object>> verify(@PathVariable Long id, @Valid @RequestBody VerifyRequest request) {
        // 校验验证
        return Result.ok(orderService.verify(id, request, UserContext.get()));
    }

    /** 分派弹窗街道办选项：按工单辖区返回（下拉数据源） */
    @GetMapping("/orders/{id}/street-options")
    public Result<List<Map<String, Object>>> streetOptions(@PathVariable Long id) {
        return Result.ok(orderService.streetOptionsFor(id, UserContext.get()));
    }

    /** 部门端申请驳回：待部门端处理 → 待管理端分派 */
    @PostMapping("/orders/{id}/apply-reject")
    public Result<Map<String, Object>> applyReject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reject_reason");
        return Result.ok(orderService.deptApplyReject(id, reason, UserContext.get()));
    }

    /** 分派至街道办（API 9.3）：缺省按工单行政区划自动匹配 */
    @PostMapping("/orders/{id}/assign-street")
    public Result<Map<String, Object>> assignStreet(@PathVariable Long id,
                                                    @RequestBody(required = false) AssignStreetRequest request) {
        // 分配街道
        return Result.ok(orderService.assignStreet(id, request == null ? null : request.streetOffice(), request == null ? null : request.remark(), UserContext.get()));
    }

    /** 部门端详情弹窗修改承办街道办（只改街道办，不改变流转状态） */
    @PostMapping("/orders/{id}/street-office")
    public Result<Map<String, Object>> streetOffice(@PathVariable Long id,
                                                    @RequestBody(required = false) Map<String, String> body) {
        // 修改街道办
        String street = body == null ? null : body.get("street_office");
        return Result.ok(orderService.updateDeptStreetOffice(id, street, UserContext.get()));
    }

    /** 部门同意驳回：街道办已驳回 → 部门已驳回（回管理端终审） */
    @PostMapping("/orders/{id}/dept-reject")
    public Result<Map<String, Object>> deptReject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String remark = body == null ? null : body.get("reject_remark");
        return Result.ok(orderService.deptReject(id, remark, UserContext.get()));
    }

    /** 部门重新分派：街道办已驳回 → 待分派 */
    @PostMapping("/orders/{id}/dept-reassign")
    public Result<Map<String, Object>> deptReassign(@PathVariable Long id) {
        return Result.ok(orderService.deptReassign(id, UserContext.get()));
    }

    /** 部门工作台首页统计（API：仅本部门工单，街道办按辖区） */
    @GetMapping("/workbench")
    public Result<Map<String, Object>> workbench() {
        return Result.ok(deptOrderService.workbenchSummary(UserContext.get()));
    }

    /** 权限内工单查询（API 9.5）；statuses：多状态集合（逗号分隔），如工作台「待审核」=PENDING_EVALUATE,EVALUATED,STREET_DONE */
    @GetMapping("/orders")
    public Result<Map<String, Object>> orders(@RequestParam(required = false) String order_no,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String statuses,
                                              @RequestParam(name = "street_office", required = false) String streetOffice,
                                              @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                              @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 执行orders操作（street_office 可选：按街道前缀进一步过滤，数据范围仍受 JWT 限制）
        List<String> statusList = (statuses == null || statuses.isBlank())
                ? null : java.util.Arrays.stream(statuses.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        PageData<Map<String, Object>> data = deptOrderService.page(
                order_no, keyword, status, statusList, streetOffice, start_time, end_time, page, pageSize, UserContext.get());
        // 部门端工单按"到达本部门时间"判新（口径同未读计数）
        return Result.page(data);
    }

    /** 权限内工单详情（API 9.12）：含命中关键词高亮、处置附件与时间线 */
    @GetMapping("/orders/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        // 执行detail操作
        return Result.ok(deptOrderService.detail(id, UserContext.get()));
    }

    /** 工单流转时间线（API 11.1.5）：角色化展示，不暴露具体人员姓名 */
    @GetMapping("/orders/{id}/timeline")
    public Result<Map<String, Object>> timeline(@PathVariable Long id) {
        // 执行timeline操作（校验辖区/部门归属，防止越权读取他人工单流转记录）
        deptOrderService.requireScope(id, UserContext.get());
        return Result.ok(Map.of("timeline", orderLogService.timeline(id, OrderLogService.TimelineEnd.DEPT)));
    }

    /** 待审核工单列表（API 11.2.1）：按街道办/处置标签/超时筛选 */
    @GetMapping("/orders/auditing")
    public Result<Map<String, Object>> auditing(@RequestParam(required = false) String street_office,
                                                @RequestParam(required = false) String dispose_tag,
                                                @RequestParam(required = false) Boolean is_overdue,
                                                @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                                @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 审核ing
        PageData<Map<String, Object>> data = deptOrderService.auditingPage(
                street_office, dispose_tag, is_overdue, start_time, end_time, page, pageSize, UserContext.get());
        return Result.page(data);
    }

    /** 部门审核通过（API 11.2.2）：待部门审核 → 待全局终审 */
    @PostMapping("/orders/{id}/audit-approve")
    public Result<Map<String, Object>> auditApprove(@PathVariable Long id) {
        // 审核Approve
        return Result.ok(orderService.auditApprove(id, UserContext.get()));
    }

    /** 部门审核退回（API 11.2.3）：退回街道办重新处理，第3次强制升级 admin 协调 */
    @PostMapping("/orders/{id}/audit-reject")
    public Result<Map<String, Object>> auditReject(@PathVariable Long id,
                                                   @Valid @RequestBody AuditRejectRequest request) {
        // 审核驳回
        return Result.ok(orderService.auditReject(id, request.rejectReason(), UserContext.get()));
    }

    /** 批量审核（API 11.2.4）：批量通过限同类处置标签，敏感/紧急工单不支持批量 */
    @PostMapping("/orders/batch-audit")
    public Result<Map<String, Object>> batchAudit(@Valid @RequestBody BatchAuditRequest request) {
        // 批量审核
        return Result.ok(orderService.batchAudit(request, UserContext.get()));
    }

    /** 处置质量评分（API 11.2.5）：1-5星 */
    @PostMapping("/orders/{id}/score")
    public Result<Map<String, Object>> score(@PathVariable Long id, @Valid @RequestBody ScoreRequest request) {
        // 评分操作
        return Result.ok(orderService.score(id, request, UserContext.get()));
    }

    /** 待审批延期申请列表（API 11.2.6，查询接口按需求 22(5) 补充） */
    @GetMapping("/delay-apply")
    public Result<List<Map<String, Object>>> delayPending() {
        // 延期Pending
        return Result.ok(deptOrderService.delayPendingList(UserContext.get()));
    }

    /** 延期审批（API 11.2.6）：通过后自动更新工单截止时间 */
    @PostMapping("/delay-apply/{id}/approve")
    public Result<Map<String, Object>> delayApprove(@PathVariable Long id,
                                                    @Valid @RequestBody DelayApproveRequest request) {
        // 延期Approve
        return Result.ok(orderService.delayApprove(id, request, UserContext.get()));
    }

    /** 部门汇总上报（API 11.2.7）：处置总量/通过率/退回率/平均时长/街道办排名/超时占比 */
    @GetMapping("/order-report")
    public Result<Map<String, Object>> orderReport(@RequestParam(defaultValue = "DAY") String period) {
        // 执行orderReport操作
        return Result.ok(orderService.orderReport(period, UserContext.get()));
    }
}