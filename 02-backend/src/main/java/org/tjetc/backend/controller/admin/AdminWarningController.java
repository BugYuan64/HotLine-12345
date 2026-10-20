package org.tjetc.backend.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.admin.WarningCaseRequest;
import org.tjetc.backend.dto.admin.WarningRecordReviewRequest;
import org.tjetc.backend.dto.admin.WarningRuleFromPredictionRequest;
import org.tjetc.backend.dto.admin.WarningRuleRequest;
import org.tjetc.backend.service.admin.WarningCaseService;
import org.tjetc.backend.service.admin.WarningService;
import org.tjetc.backend.service.admin.SensitiveEventService;
import org.tjetc.backend.service.common.UnreadService;
import org.tjetc.backend.service.staff.WarningDispatchService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端预警配置管理接口（API 8.1/8.2）：
 * 预警规则维护（新增/编辑/启停/删除）+ 预警记录查询（含 AI 总结与应对措施）
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
public class AdminWarningController {

    private final WarningService warningService;
    private final WarningCaseService warningCaseService;
    private final SensitiveEventService sensitiveEventService;
    private final WarningDispatchService warningDispatchService;
    private final UnreadService unreadService;

    /** 预警规则列表（API 8.1 GET） */
    @GetMapping("/warnings/rules")
    public Result<List<Map<String, Object>>> rules() {
        // 执行rules操作
        return Result.ok(warningService.rules());
    }

    /** 新增预警规则（API 8.1 POST） */
    @PostMapping("/warnings/rules")
    public Result<Map<String, Object>> addRule(@Valid @RequestBody WarningRuleRequest request) {
        // 添加Rule
        return Result.ok(warningService.addRule(request));
    }

    /** 预判结果一键生成预警规则草稿（DRAFT）：生成后需人工确认启用 */
    @PostMapping("/warnings/rules/from-prediction")
    public Result<Map<String, Object>> createRuleFromPrediction(@Valid @RequestBody WarningRuleFromPredictionRequest request) {
        // 预判生成草稿规则
        return Result.ok(warningService.createRuleFromPrediction(request));
    }

    /** 二级分类建议列表（API 8.1）：按一级分类返回已用于工单的二级分类，供前端规则弹窗可选用 */
    @GetMapping("/warnings/rules/sub-categories")
    public Result<List<String>> subCategories(@RequestParam(required = false) String category) {
        // 二级分类建议
        return Result.ok(warningService.listSubCategories(category));
    }

    /** 编辑/启停预警规则（API 8.1 PUT） */
    @PutMapping("/warnings/rules/{id}")
    public Result<Map<String, Object>> updateRule(@PathVariable Long id,
                                                  @Valid @RequestBody WarningRuleRequest request) {
        // 更新Rule
        return Result.ok(warningService.updateRule(id, request));
    }

    /** 删除预警规则（API 8.1 DELETE） */
    @DeleteMapping("/warnings/rules/{id}")
    public Result<Map<String, Object>> deleteRule(@PathVariable Long id) {
        // 删除Rule
        return Result.ok(warningService.deleteRule(id));
    }

    /** 标记预警已响应（需求 11(4)）：关闭预警响应闭环 */
    @PutMapping("/warnings/{id}/respond")
    public Result<Map<String, Object>> respond(@PathVariable Long id) {
        // 标记Respond
        return Result.ok(warningService.respond(id));
    }

    /** 手动立即扫描预警（A5）：即时触发规则扫描，便于改完规则后验证触发效果 */
    @PostMapping("/warnings/scan")
    public Result<Map<String, Object>> scan() {
        // 执行Scan
        int triggered = warningService.runWarningScan();
        return Result.ok(Map.of("triggered", triggered));
    }

    /** 预警记录查询（API 8.2）：按等级/分类/辖区/审核状态/触发时间筛选 */
    @GetMapping("/warnings")
    public Result<Map<String, Object>> warnings(
            @RequestParam(required = false) String warning_level,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 预警记录列表
        PageData<Map<String, Object>> data =
                warningService.pageRecords(warning_level, category, region, status, start_time, end_time, page, pageSize);
        unreadService.decorateNew(UserContext.get(), UnreadService.MODULE_WARNING, data, "trigger_time");
        return Result.page(data);
    }

    /** 审核发送预警（方案B）：待审核 → 已发送，并推送站内消息 */
    @PutMapping("/warnings/{id}/send")
    public Result<Map<String, Object>> send(@PathVariable Long id) {
        // 发送预警
        return Result.ok(warningService.send(id));
    }

    /** 审核驳回预警（方案B）：待审核 → 已驳回，保留备查 */
    @PutMapping("/warnings/{id}/reject")
    public Result<Map<String, Object>> reject(@PathVariable Long id) {
        // 驳回预警
        return Result.ok(warningService.reject(id));
    }

    /** 审核中编辑 AI 总结与应对措施（方案B） */
    @PutMapping("/warnings/{id}/content")
    public Result<Map<String, Object>> updateContent(@PathVariable Long id,
                                                     @RequestBody WarningRecordReviewRequest request) {
        // 编辑预警内容
        return Result.ok(warningService.updateContent(id, request));
    }

    /** 从预警记录生成预警规则草稿（方案B） */
    @PostMapping("/warnings/{id}/generate-rule")
    public Result<Map<String, Object>> generateRuleFromRecord(@PathVariable Long id) {
        // 记录生成草稿规则
        return Result.ok(warningService.generateRuleFromRecord(id));
    }

    /** 预警分发进度（只读）：查看该预警已分发街道办及各自响应状态 */
    @GetMapping("/warnings/{id}/dispatches")
    public Result<List<Map<String, Object>>> dispatches(@PathVariable Long id) {
        // 分发进度
        return Result.ok(warningDispatchService.progress(id));
    }

    /** 敏感事件查询（P0-3 / 需求 9(9) 管理端展示） */
    @GetMapping("/warnings/sensitive-events")
    public Result<Map<String, Object>> sensitiveEvents(@RequestParam(required = false) String riskLevel,
                                                       @RequestParam(required = false) String handleStatus,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 分页查询敏感事件
        PageData<Map<String, Object>> data =
                sensitiveEventService.pageSensitive(riskLevel, handleStatus, page, pageSize);
        // 敏感事件"新"角标（按已读游标判新）
        unreadService.decorateNew(UserContext.get(), UnreadService.MODULE_SENSITIVE_EVENT, data, "trigger_time");
        return Result.page(data);
    }

    /** 标记敏感事件已处理（P0-3） */
    @PutMapping("/warnings/sensitive-events/{id}/handle")
    public Result<Map<String, Object>> handleSensitive(@PathVariable Long id) {
        // 标记已处理
        return Result.ok(sensitiveEventService.handleSensitive(id));
    }

    // ------------------------------------------------------------ 优秀处置案例库（预警建议 RAG 检索来源）

    /** 处置案例分页查询（按关键词/分类/辖区/状态筛选） */
    @GetMapping("/warnings/cases")
    public Result<Map<String, Object>> cases(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String category,
                                             @RequestParam(required = false) String region,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 执行cases操作
        PageData<Map<String, Object>> data =
                warningCaseService.page(keyword, category, region, status, page, pageSize);
        return Result.page(data);
    }

    /** 新增处置案例 */
    @PostMapping("/warnings/cases")
    public Result<Map<String, Object>> addCase(@Valid @RequestBody WarningCaseRequest request) {
        // 添加Case
        return Result.ok(warningCaseService.add(request));
    }

    /** 编辑处置案例 */
    @PutMapping("/warnings/cases/{id}")
    public Result<Map<String, Object>> updateCase(@PathVariable Long id,
                                                  @Valid @RequestBody WarningCaseRequest request) {
        // 更新Case
        return Result.ok(warningCaseService.update(id, request));
    }

    /** 删除处置案例 */
    @DeleteMapping("/warnings/cases/{id}")
    public Result<Map<String, Object>> deleteCase(@PathVariable Long id) {
        // 删除Case
        return Result.ok(warningCaseService.delete(id));
    }

    /** 处置案例 Excel 批量导入 */
    @PostMapping("/warnings/cases/import")
    public Result<Map<String, Object>> importCases(@RequestParam("file") MultipartFile file) {
        // 导入Cases
        return Result.ok(warningCaseService.importExcel(file));
    }
}