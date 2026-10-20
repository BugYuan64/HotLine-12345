package org.tjetc.backend.controller.staff;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.service.admin.AuditLogService;
import org.tjetc.backend.service.common.UnreadService;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 部门端系统审计接口：本部门权限范围内的日志
 * = 本部门所有账号的操作 + 归属本部门的「收到工单」记录（操作人是管理端账号名）
 * 口径与管理端「部门审计」一致，前端不可越权。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/staff")
public class StaffLogController {

    private final AuditLogService auditLogService;
    private final UnreadService unreadService;

    @GetMapping("/logs")
    public Result<Map<String, Object>> logs(@RequestParam(name = "appeal_keyword", required = false) String appealKeyword,
                                            @RequestParam(required = false) String module,
                                            @RequestParam(required = false) Long id,
                                            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 本部门权限范围：dept=当前部门代号（URBAN 等）+ 本账号区县（56部门口径按区县隔离），复用部门审计口径
        String dept = UserContext.get().userType();
        String countyCode = UserContext.get().regionCode();
        PageData<Map<String, Object>> data =
                auditLogService.deptPage(null, module, id, dept, countyCode, start_time, end_time, page, pageSize, appealKeyword);
        return Result.page(data);
    }
}
