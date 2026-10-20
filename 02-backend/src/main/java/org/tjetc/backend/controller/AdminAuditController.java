package org.tjetc.backend.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.AccountAuditBatchRequest;
import org.tjetc.backend.dto.AccountAuditRejectRequest;
import org.tjetc.backend.service.admin.AccountAuditService;
import org.tjetc.backend.service.common.UnreadService;

import java.util.Map;

/**
 * 账号审核管理接口（API 5.3/5.4/5.5/5.7）：
 * 审核列表、审核通过、审核驳回、批量审核（仅 ADMIN 可访问，AuthInterceptor 按前缀隔离权限）
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminAuditController {

    private final AccountAuditService accountAuditService;
    private final UnreadService unreadService;

    public AdminAuditController(AccountAuditService accountAuditService, UnreadService unreadService) {
        this.accountAuditService = accountAuditService;
        this.unreadService = unreadService;
    }

    /** 账号审核列表（API 5.3） */
    @GetMapping("/audits")
    public Result<Map<String, Object>> audits(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String name,
                                              @RequestParam(required = false) String job_no,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 审核列表
        PageData<Map<String, Object>> data = accountAuditService.page(status, name, job_no, page, pageSize);
        // 列表行"新"角标（申请时间缺失的行回退创建时间，口径同未读计数）
        unreadService.decorateNew(UserContext.get(), UnreadService.MODULE_ACCOUNT_AUDIT, data, "apply_time", "create_time");
        return Result.page(data);
    }

    /** 账号审核通过（API 5.4） */
    @PostMapping("/audits/{id}/approve")
    public Result<Map<String, Object>> approve(@PathVariable Long id) {
        // 审批通过
        return Result.ok(accountAuditService.approve(id, UserContext.get()));
    }

    /** 账号审核驳回（API 5.5） */
    @PostMapping("/audits/{id}/reject")
    public Result<Map<String, Object>> reject(@PathVariable Long id,
                                              @Valid @RequestBody AccountAuditRejectRequest request) {
        // 驳回操作
        return Result.ok(accountAuditService.reject(id, request.rejectReason(), UserContext.get()));
    }

    /** 账号批量审核（API 5.7） */
    @PostMapping("/audits/batch")
    public Result<Map<String, Object>> batch(@Valid @RequestBody AccountAuditBatchRequest request) {
        // 批量
        return Result.ok(accountAuditService.batch(request, UserContext.get()));
    }
}