package org.tjetc.backend.controller.admin;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.tjetc.backend.dto.OrderClassifyCorrectRequest;
import org.tjetc.backend.dto.OrderCreateRequest;
import org.tjetc.backend.dto.OrderUpdateRequest;
import org.tjetc.backend.dto.admin.BatchFinalRequest;
import org.tjetc.backend.dto.admin.FinalRejectRequest;
import org.tjetc.backend.dto.admin.OrderDispatchRequest;
import org.tjetc.backend.dto.admin.ReopenApproveRequest;
import org.tjetc.backend.service.admin.AppealAutoProcessService;
import org.tjetc.backend.service.admin.OrderService;
import org.tjetc.backend.service.admin.SseEventService;
import org.tjetc.backend.service.common.UnreadService;
import org.tjetc.backend.util.ExcelUtil;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端工单接口（API 6.2~6.10 / 11.3 章）：
 * 工单查询、审核确认、等级升级、手动录入/编辑/删除/批量导入、分类人工修正、手动分派/转派、
 * 待终审列表、全链路信息、终审通过/退回/批量终审、手动归档、归档数据管理、重开申请审批
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOrderController {

    private final OrderService orderService;
    private final UnreadService unreadService;
    private final AppealAutoProcessService appealAutoProcessService;
    private final SseEventService sseEventService;

    // ==================== P1 工单管理（API 6.2~6.9） ====================

    /** 管理端工单多条件分页查询（API 6.2）；archived null=全部/0=未归档(工单管理)/1=已归档(终审归档页)；
     *  time_start/time_end：归档页时间段筛选（创建与完成时间均落在区间内的归档工单） */
    @GetMapping("/orders")
    public Result<Map<String, Object>> orders(@RequestParam(required = false) String order_no,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String level,
                                              @RequestParam(required = false) String region,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String start_date,
                                              @RequestParam(name = "time_start", required = false) String timeStart,
                                              @RequestParam(name = "time_end", required = false) String timeEnd,
                                              @RequestParam(required = false) Integer archived,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        PageData<Map<String, Object>> data =
                orderService.listOrders(order_no, category, level, region, status, keyword, start_date,
                        timeStart, timeEnd, archived, page, pageSize);
        // 列表行"新"角标（工单按更新时间判新：新增与状态改变都会标红，重复变更只算一次）
        Map<String, Object> resp = data.toMap();
        // 工单页分派进度：追加「当前筛选列表中的待分派数」（分母 = 列表总数 total，列表为空时前端显示 100%）
        if (archived == null || archived != 1) {
            resp.put("list_pending_count",
                    orderService.countPendingPage(order_no, category, level, region, status, keyword, start_date, archived));
        }
        return Result.ok(resp);
    }

    /** 取消确认：待分派回退待审核 */
    @PostMapping("/orders/{id}/cancel-confirm")
    public Result<Map<String, Object>> cancelConfirm(@PathVariable Long id) {
        return Result.ok(orderService.cancelConfirm(id, UserContext.get()));
    }

    /** 工单等级升级（API 6.4） */
    @PostMapping("/orders/{id}/upgrade")
    public Result<Map<String, Object>> upgrade(@PathVariable Long id,
                                               @Valid @RequestBody org.tjetc.backend.dto.OrderUpgradeRequest request) {
        // 升级工单
        return Result.ok(orderService.upgradeOrder(id, request.newLevel(), UserContext.get()));
    }

    /** 手动录入工单（API 6.5）：录入后自动 AI 智能分类 */
    @PostMapping("/orders")
    public Result<Map<String, Object>> create(@Valid @RequestBody OrderCreateRequest request) {
        // 创建新数据
        return Result.ok(orderService.createOrder(request, UserContext.get()));
    }

    /** 编辑工单（API 6.6） */
    @PutMapping("/orders/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id,
                                              @Valid @RequestBody OrderUpdateRequest request) {
        // 更新数据
        return Result.ok(orderService.updateOrder(id, request, UserContext.get()));
    }

    /** 删除工单（API 6.7）：逻辑删除，历史可追溯 */
    @DeleteMapping("/orders/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Long id) {
        // 删除数据
        return Result.ok(orderService.deleteOrder(id, UserContext.get()));
    }

    /** Excel 批量导入（API 6.8）：模板列「诉求标题|诉求内容|所属辖区|工单等级（选填）」 */
    @PostMapping("/orders/import")
    public Result<Map<String, Object>> importOrders(@RequestParam("file") MultipartFile file) {
        // 导入Orders
        return Result.ok(orderService.importOrders(file, UserContext.get()));
    }

    /** 批量导出工单（API 6.8 对称）：按勾选 id 流式回写 Excel（零落盘），前 4 列对齐导入模板可回导，附加工单状态列 */
    @PostMapping("/orders/export")
    public void exportOrders(@RequestBody Map<String, Object> body, HttpServletResponse response) throws IOException {
        // 导出Orders
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) body.get("ids");
        XSSFWorkbook workbook = orderService.exportOrders(ids, UserContext.get());
        ExcelUtil.writeResponse(workbook, "orders_export.xlsx", response);
    }

    /** 分类人工修正（API 6.9）：修正沉淀为纠错样本反哺算法 */
    @PostMapping("/orders/{id}/classify-correct")
    public Result<Map<String, Object>> classifyCorrect(@PathVariable Long id,
                                                       @Valid @RequestBody OrderClassifyCorrectRequest request) {
        // 分类Correct
        return Result.ok(orderService.correctCategory(id, request, UserContext.get()));
    }

    // ==================== 工单分派与终审归档（API 6.10 / 11.3 章） ====================

    /** 管理员手动分派/转派（API 6.10） */
    @PostMapping("/orders/{id}/dispatch")
    public Result<Map<String, Object>> dispatch(@PathVariable Long id,
                                                @Valid @RequestBody OrderDispatchRequest request) {
        // 派单操作
        return Result.ok(orderService.dispatch(id, request, UserContext.get()));
    }

    /** 待终审工单列表（API 11.3.1） */
    @GetMapping("/orders/final-auditing")
    public Result<Map<String, Object>> finalAuditing(@RequestParam(required = false) String department,
                                                     @RequestParam(required = false) String street_office,
                                                     @RequestParam(required = false) String dispose_tag,
                                                     @RequestParam(required = false) Boolean is_overdue,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 结案Auditing
        PageData<Map<String, Object>> data =
                orderService.finalAuditingPage(department, street_office, dispose_tag, is_overdue, page, pageSize);
        return Result.page(data);
    }

    /** 全链路信息查看（API 11.3.2） */
    @GetMapping("/orders/{id}/full-chain")
    public Result<Map<String, Object>> fullChain(@PathVariable Long id) {
        // 执行fullChain操作
        return Result.ok(orderService.fullChain(id));
    }

    /** 工单流转时间线（工单管理行展开用）：order_log 全流程记录，最后一条=当前进行步骤 */
    @GetMapping("/orders/{id}/timeline")
    public Result<Map<String, Object>> timeline(@PathVariable Long id) {
        return Result.ok(Map.of("timeline", orderService.timelineOf(id)));
    }

    /** 终审确认（新流程）：已办结/已评价 → 确认归档 或 退回 */
    @PostMapping("/orders/{id}/final-confirm")
    public Result<Map<String, Object>> finalConfirm(@PathVariable Long id,
                                                    @RequestBody(required = false) Map<String, String> body) {
        String action = body == null ? "archive" : body.getOrDefault("action", "archive");
        String reason = body == null ? null : body.get("reject_reason");
        String target = body == null ? null : body.get("reject_target");
        return Result.ok(orderService.finalConfirm(id, action, reason, target, UserContext.get()));
    }

    /** 工单详情（工单分页「查看」弹窗用，含合并子诉求） */
    @GetMapping("/orders/{id}/detail")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(orderService.getOrderDetail(id));
    }

    /** 终审通过（API 11.3.3）：工单办结并自动通知市民评价 */
    @PostMapping("/orders/{id}/final-approve")
    public Result<Map<String, Object>> finalApprove(@PathVariable Long id) {
        // 结案Approve
        return Result.ok(orderService.finalApprove(id, UserContext.get()));
    }

    /** 终审退回（API 11.3.4）：退回部门→待部门分派；直接退回街道办→处理中 */
    @PostMapping("/orders/{id}/final-reject")
    public Result<Map<String, Object>> finalReject(@PathVariable Long id,
                                                   @Valid @RequestBody FinalRejectRequest request) {
        // 结案驳回
        return Result.ok(orderService.finalReject(id, request, UserContext.get()));
    }

    /** 批量终审（API 11.3.5）：批量通过限同一部门，敏感/紧急工单不支持批量 */
    @PostMapping("/orders/batch-final-audit")
    public Result<Map<String, Object>> batchFinalAudit(@Valid @RequestBody BatchFinalRequest request) {
        // 批量FinalAudit
        return Result.ok(orderService.batchFinalAudit(request, UserContext.get()));
    }

    /** 手动归档（API 11.3.6）：归档后只读 */
    @PostMapping("/orders/{id}/archive")
    public Result<Map<String, Object>> archive(@PathVariable Long id) {
        // 执行archive操作
        return Result.ok(orderService.archive(id, UserContext.get()));
    }

    /** 终审重新分派：部门已驳回 → 待管理端分派 */
    @PostMapping("/orders/{id}/reassign")
    public Result<Map<String, Object>> reassign(@PathVariable Long id) {
        return Result.ok(orderService.reassign(id, UserContext.get()));
    }

    /** 重新分类：body 为空=打开弹窗留痕；携带 category/department=保存新分类并退回「待分派」（分派由「分派」按钮完成） */
    @PostMapping("/orders/{id}/reclassify")
    public Result<Map<String, Object>> reclassify(@PathVariable Long id,
                                                  @RequestBody(required = false) Map<String, String> body) {
        return Result.ok(orderService.markReclassify(id, body, UserContext.get()));
    }

    /** 归档数据管理（API 11.3.7）：筛选分页列表 */
    @GetMapping("/orders/archives")
    public Result<Map<String, Object>> archives(@RequestParam(required = false) String year,
                              @RequestParam(required = false) String department,
                              @RequestParam(required = false) String category,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 执行archives操作
        PageData<Map<String, Object>> data =
                orderService.archivesPage(year, department, category, page, pageSize);
        return Result.page(data);
    }

    /** 归档数据导出（API 11.3.7）：按筛选条件流式回写 Excel（零落盘） */
    @GetMapping("/orders/archives/export")
    public void exportArchives(@RequestParam(required = false) String year,
                               @RequestParam(required = false) String department,
                               @RequestParam(required = false) String category,
                               HttpServletResponse response) throws IOException {
        XSSFWorkbook workbook = orderService.exportArchives(year, department, category);
        ExcelUtil.writeResponse(workbook, "archives_export.xlsx", response);
    }

    /** 重开申请列表（API 11.3.8 审批列表，文档未定义查询接口，按需求 24(3) 补充） */
    @GetMapping("/orders/reopen-applications")
    public Result<Map<String, Object>> reopenApplications(@RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 重开Applications
        PageData<Map<String, Object>> data = orderService.reopenApplicationsPage(page, pageSize);
        return Result.page(data);
    }

    /** 重开申请审批（API 11.3.8）：通过后工单重新进入分派流程，最多重开1次 */
    @PostMapping("/orders/{id}/reopen-approve")
    public Result<Map<String, Object>> reopenApprove(@PathVariable Long id,
                                                     @Valid @RequestBody ReopenApproveRequest request) {
        // 重开审批
        return Result.ok(orderService.reopenApprove(id, request, UserContext.get()));
    }

    // ==================== 自动派单（工单管理：开关 + 30 秒周期扫描 + SSE 状态推送） ====================

    /** 自动派单开关配置读取（GET） */
    @GetMapping("/orders/auto-dispatch/config")
    public Result<Map<String, Object>> autoDispatchConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("autoDispatch", appealAutoProcessService.isAutoDispatchEnabled());
        data.put("enabled", appealAutoProcessService.isAutoDispatchEnabled());
        data.put("operator", operatorOf());
        data.put("pendingCount", orderService.countPendingDispatch());
        data.put("totalOrders", orderService.countAllOrders());
        return Result.ok(data);
    }

    /** 自动派单开关设置（PUT）：body {autoDispatch: true|false} */
    @PutMapping("/orders/auto-dispatch/config")
    public Result<Map<String, Object>> setAutoDispatchConfig(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("autoDispatch"));
        appealAutoProcessService.setAutoDispatch(enabled);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("autoDispatch", enabled);
        data.put("enabled", enabled);
        data.put("operator", operatorOf());
        data.put("pendingCount", orderService.countPendingDispatch());
        data.put("totalOrders", orderService.countAllOrders());
        return Result.ok(data);
    }

    /** 立即启动一轮自动派单（手动触发，POST） */
    @PostMapping("/orders/auto-dispatch")
    public Result<Map<String, Object>> startAutoDispatch() {
        String taskId = appealAutoProcessService.startAutoDispatch();
        return Result.ok(Map.of("taskId", taskId != null ? taskId : ""));
    }

    /** 查询自动派单进度（GET）：开关状态 + 任务进度 + 待分配数 */
    @GetMapping("/orders/auto-dispatch/status")
    public Result<Map<String, Object>> autoDispatchStatus() {
        var task = appealAutoProcessService.getAutoDispatchTask();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", appealAutoProcessService.isAutoDispatchEnabled());
        data.put("operator", operatorOf());
        data.put("pendingCount", orderService.countPendingDispatch());
        data.put("totalOrders", orderService.countAllOrders());
        data.put("totalOrders", orderService.countAllOrders());
        if (task == null) {
            data.put("active", false);
        } else {
            data.put("active", !task.finished && !task.cancelled);
            data.put("taskId", task.taskId);
            data.put("total", task.totalCount);
            data.put("done", task.doneCount());
            data.put("inProgress", task.inProgressIds.stream().toList());
            data.put("finished", task.finished);
            data.put("cancelled", task.cancelled);
            data.put("results", task.results);
        }
        return Result.ok(data);
    }

    /** 取消自动派单（POST） */
    @PostMapping("/orders/auto-dispatch/cancel")
    public Result<Map<String, Object>> cancelAutoDispatch() {
        appealAutoProcessService.cancelAutoDispatch();
        return Result.ok(Map.of("result", "cancelled"));
    }

    /** 自动派单状态 SSE 流（前端订阅，后端派单状态变化时被动推送 status-change） */
    @GetMapping("/orders/auto-dispatch/stream")
    public SseEmitter autoDispatchStream() {
        return sseEventService.subscribe(SseEventService.TYPE_DISPATCH, UserContext.get().userId());
    }

    private String operatorOf() {
        LoginUser user = UserContext.get();
        return user != null && user.jobNo() != null && !user.jobNo().isBlank()
                ? "ADMIN-" + user.jobNo() : "SYSTEM";
    }
}