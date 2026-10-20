package org.tjetc.backend.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.admin.AppealCreateRequest;
import org.tjetc.backend.dto.admin.AppealUpdateRequest;
import org.tjetc.backend.service.admin.AppealAutoProcessService;
import org.tjetc.backend.service.admin.AppealManageService;
import org.tjetc.backend.service.admin.SseEventService;
import org.tjetc.backend.service.common.UnreadService;

import java.util.List;
import java.util.Map;

/**
 * 诉求管理接口（API 6.1）：
 * 管理员查询全平台诉求，含清洗留痕、同源关联、拆分关系，供追溯查看；
 * 并支持诉求新增、编辑、删除（仅 ADMIN 可访问）
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
public class AdminAppealController {

    private final AppealManageService appealManageService;
    private final AppealAutoProcessService appealAutoProcessService;
    private final SseEventService sseEventService;
    private final UnreadService unreadService;

    /** 诉求查询与追溯（API 6.1 GET） */
    @GetMapping("/appeals")
    public Result<Map<String, Object>> appeals(@RequestParam(required = false) String appeal_no,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String scope,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        PageData<Map<String, Object>> data =
                appealManageService.page(appeal_no, status, keyword, scope, page, pageSize);
        // 列表行"新"角标：scope=child 判子诉求模块，否则判父诉求模块
        String module = "child".equals(scope)
                ? UnreadService.MODULE_APPEAL_CHILD : UnreadService.MODULE_APPEAL_PARENT;
        return Result.page(data);
    }

    /** 诉求附件列表（市民提交时上传；scope=parent/child；带 MinIO 预签名下载链接） */
    @GetMapping("/appeals/{id}/attachments")
    public Result<java.util.List<Map<String, Object>>> attachments(@PathVariable Long id,
                                                                   @RequestParam(defaultValue = "parent") String scope) {
        return Result.ok(appealManageService.attachments(id, scope));
    }

    /** 新增诉求（POST） */
    @PostMapping("/appeals")
    public Result<Map<String, Object>> create(@Valid @RequestBody AppealCreateRequest request) {
        return Result.ok(appealManageService.create(request, UserContext.get()));
    }

    /** 编辑诉求（PUT） */
    @PutMapping("/appeals/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id,
                                              @RequestParam(required = false) String scope,
                                              @Valid @RequestBody AppealUpdateRequest request) {
        return Result.ok(appealManageService.update(id, scope, request, UserContext.get()));
    }

    /** 删除诉求（DELETE） */
    @DeleteMapping("/appeals/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Long id,
                                              @RequestParam(required = false) String scope) {
        return Result.ok(appealManageService.delete(id, scope, UserContext.get()));
    }

    /** 查询某父诉求的全部子诉求（GET） */
    @GetMapping("/appeals/{id}/children")
    public Result<List<Map<String, Object>>> children(@PathVariable Long id) {
        return Result.ok(appealManageService.children(id));
    }

    /** 查询子诉求对应工单详情（子诉求列表「查看工单」弹窗用） */
    @GetMapping("/appeals/{childId}/order")
    public Result<Map<String, Object>> childOrder(@PathVariable Long childId) {
        return Result.ok(appealManageService.getChildOrder(childId));
    }

    /** 手动触发一次诉求自动处理（拆分 + 建单），便于联调（POST） */
    @PostMapping("/appeals/auto-process")
    public Result<Map<String, Object>> autoProcess() {
        int handled = appealAutoProcessService.processAll();
        Map<String, Object> data = Map.of("handled", handled);
        return Result.ok(data);
    }

    /** 撤回父诉求拆分：删除子诉求，父诉求回退为 PENDING_AI（POST） */
    @PostMapping("/appeals/{id}/withdraw-split")
    public Result<Map<String, Object>> withdrawSplit(@PathVariable Long id) {
        appealAutoProcessService.withdrawSplit(id);
        return Result.ok(Map.of("result", "success"));
    }

    /** 查询自动/手动审批配置 */
    @GetMapping("/appeals/config")
    public Result<Map<String, Object>> getConfig() {
        return Result.ok(Map.of(
            "autoSplit", appealAutoProcessService.isAutoSplitEnabled(),
            "autoOrder", appealAutoProcessService.isAutoOrderEnabled()
        ));
    }

    /** 设置自动/手动审批配置 */
    @PutMapping("/appeals/config")
    public Result<Map<String, Object>> setConfig(@RequestBody Map<String, Boolean> body) {
        if (body.containsKey("autoSplit")) {
            appealAutoProcessService.setAutoSplit(body.get("autoSplit"));
        }
        if (body.containsKey("autoOrder")) {
            appealAutoProcessService.setAutoOrder(body.get("autoOrder"));
        }
        return Result.ok(Map.of("result", "success"));
    }

    /** 按勾选的子诉求ID生成工单（手动触发，异步，POST）：立即返回 taskId，前端轮询进度 */
    @PostMapping("/appeals/create-orders")
    public Result<Map<String, Object>> createOrders(@RequestBody List<Long> childIds) {
        String taskId = appealAutoProcessService.startCreateOrders(childIds);
        return Result.ok(Map.of("taskId", taskId));
    }

    /** 查询生成工单任务进度（GET，前端轮询） */
    @GetMapping("/appeals/create-orders/task/{taskId}")
    public Result<Map<String, Object>> createOrderTask(@PathVariable String taskId) {
        var task = appealAutoProcessService.getCreateOrderTask(taskId);
        if (task == null) {
            return Result.fail(ResultCode.PARAM_ERROR, "生成工单任务不存在或已过期");
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("taskId", task.taskId);
        data.put("total", task.total);
        data.put("done", task.doneCount());
        data.put("finished", task.finished);
        data.put("results", task.results);
        return Result.ok(data);
    }

    /** 按勾选的父诉求ID手动拆分（异步，POST）：立即返回 taskId，前端轮询进度 */
    @PostMapping("/appeals/split")
    public Result<Map<String, Object>> split(@RequestBody List<Long> parentIds) {
        String taskId = appealAutoProcessService.startSplit(parentIds);
        return Result.ok(Map.of("taskId", taskId));
    }

    /** 查询拆分任务进度（GET，前端轮询） */
    @GetMapping("/appeals/split-task/{taskId}")
    public Result<Map<String, Object>> splitTask(@PathVariable String taskId) {
        var task = appealAutoProcessService.getSplitTask(taskId);
        if (task == null) {
            return Result.fail(ResultCode.PARAM_ERROR, "拆分任务不存在或已过期");
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("taskId", task.taskId);
        data.put("total", task.total);
        data.put("done", task.doneCount());
        data.put("finished", task.finished);
        data.put("results", task.results);
        return Result.ok(data);
    }

    // ==================== 自动模式：全量拆分/建单 + 可取消 + 进度追踪 ====================

    /** 启动自动拆分（全量 PENDING_AI，POST） */
    @PostMapping("/appeals/auto-split")
    public Result<Map<String, Object>> startAutoSplit() {
        String taskId = appealAutoProcessService.startAutoSplit();
        return Result.ok(Map.of("taskId", taskId != null ? taskId : ""));
    }

    /** 查询自动拆分进度（GET） */
    @GetMapping("/appeals/auto-split/status")
    public Result<Map<String, Object>> autoSplitStatus() {
        var task = appealAutoProcessService.getAutoSplitTask();
        return Result.ok(autoTaskToMap(task));
    }

    /** 取消自动拆分（POST） */
    @PostMapping("/appeals/auto-split/cancel")
    public Result<Map<String, Object>> cancelAutoSplit() {
        appealAutoProcessService.cancelAutoSplit();
        return Result.ok(Map.of("result", "cancelled"));
    }

    /** 启动自动建单（全量 PROCESSING，POST） */
    @PostMapping("/appeals/auto-create-orders")
    public Result<Map<String, Object>> startAutoCreateOrders() {
        String taskId = appealAutoProcessService.startAutoCreateOrders();
        return Result.ok(Map.of("taskId", taskId != null ? taskId : ""));
    }

    /** 查询自动建单进度（GET） */
    @GetMapping("/appeals/auto-create-orders/status")
    public Result<Map<String, Object>> autoCreateOrdersStatus() {
        var task = appealAutoProcessService.getAutoCreateOrderTask();
        return Result.ok(autoTaskToMap(task));
    }

    /** 取消自动建单（POST） */
    @PostMapping("/appeals/auto-create-orders/cancel")
    public Result<Map<String, Object>> cancelAutoCreateOrders() {
        appealAutoProcessService.cancelAutoCreateOrders();
        return Result.ok(Map.of("result", "cancelled"));
    }

    private Map<String, Object> autoTaskToMap(AppealAutoProcessService.AutoTask task) {
        if (task == null) {
            return Map.of("active", false);
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        // 任务结束（完成/取消）后 active 置 false，前端据此停止轮询
        data.put("active", !task.finished && !task.cancelled);
        data.put("taskId", task.taskId);
        data.put("total", task.totalCount);
        data.put("done", task.doneCount());
        data.put("inProgress", task.inProgressIds.stream().toList());
        data.put("finished", task.finished);
        data.put("cancelled", task.cancelled);
        data.put("results", task.results);
        return data;
    }

    /** 父诉求拆分状态 SSE 流（前端订阅，后端状态变化时被动推送） */
    @GetMapping("/appeals/auto/split/stream")
    public SseEmitter splitStream() {
        return sseEventService.subscribe(SseEventService.TYPE_SPLIT, UserContext.get().userId());
    }

    /** 子诉求生成工单状态 SSE 流（前端订阅，后端状态变化时被动推送） */
    @GetMapping("/appeals/auto/order/stream")
    public SseEmitter orderStream() {
        return sseEventService.subscribe(SseEventService.TYPE_ORDER, UserContext.get().userId());
    }
}