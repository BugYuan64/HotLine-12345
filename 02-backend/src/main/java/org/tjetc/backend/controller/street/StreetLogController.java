package org.tjetc.backend.controller.street;

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
 * 街道办端系统审计接口（/api/v1/street/logs）：仅查询本账号（操作人=STREET-{当前jobNo}）的日志
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/street")
public class StreetLogController {

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
        // 街道办视角：本账号操作（operator=STREET-{jobNo}）+ 归属本区县（target_type=STREET:region）的「收到工单」
        // scope 格式：STREET:{operator}:{targetMatch}，AuditLogService 按冒号拆三段
        String operator = UserContext.get().userType() + "-" + UserContext.get().jobNo();
        String scope = "STREET:" + operator + ":" + "STREET:" + UserContext.get().region();
        PageData<Map<String, Object>> data =
                auditLogService.page(null, null, module, id, start_time, end_time, page, pageSize,
                        "STREET", scope, appealKeyword);
        return Result.page(data);
    }
}
