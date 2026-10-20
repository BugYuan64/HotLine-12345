package org.tjetc.backend.controller.admin;

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
 * 管理端系统审计接口（API 8.6）：
 * 登录审计 / 数据操作 / 权限操作 / AI 生成内容审计日志查询，日志不可删除/修改
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
public class AdminLogController {

    private final AuditLogService auditLogService;
    private final UnreadService unreadService;

    /**
     * 街道办审计（管理端）：所有街道办端系统日志视角（各街道办账号操作 + 归属街道办的收到工单记录）。
     * county 参数：区县名（海港区等），传入则只看该区县街道办，缺省返回全部街道办记录。
     */
    @GetMapping("/street-logs")
    public Result<Map<String, Object>> streetLogs(@RequestParam(required = false) String log_type,
                                                  @RequestParam(name = "appeal_keyword", required = false) String appealKeyword,
                                                  @RequestParam(required = false) String module,
                                                  @RequestParam(required = false) Long id,
                                                  @RequestParam(required = false) String county,
                                                  @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                                  @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        PageData<Map<String, Object>> data =
                auditLogService.streetPage(log_type, module, id, county, start_time, end_time, page, pageSize, appealKeyword);
        return Result.page(data);
    }

    /**
     * 系统日志与 AI 生成内容审计查询（API 8.6）
     */
    @GetMapping("/logs")
    public Result<Map<String, Object>> logs(@RequestParam(required = false) String log_type,
                                           @RequestParam(name = "appeal_keyword", required = false) String appealKeyword,
                                            @RequestParam(required = false) String operator,
                                            @RequestParam(required = false) String module,
                                            @RequestParam(required = false) Long id,
                                            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 管理端视角：排除「收到工单」类（发起方只看派发，收到方才看收到）
        PageData<Map<String, Object>> data =
                auditLogService.page(log_type, operator, module, id, start_time, end_time, page, pageSize,
                        "ADMIN", null, appealKeyword);
        // 管理端系统审计"新"角标（按已读游标判新）
        return Result.page(data);
    }
    /**
     * 部门审计（管理端）：所有部门端的系统日志视角（各账号自身操作 + 归属该部门的收到工单记录）。
     * dept 参数：部门代号（URBAN/TRAFFIC/...），传入则只看该部门，缺省返回全部部门端记录。
     */
    @GetMapping("/dept-logs")
    public Result<java.util.Map<String, Object>> deptLogs(@RequestParam(required = false) String log_type,
                                                          @RequestParam(name = "appeal_keyword", required = false) String appealKeyword,
                                                          @RequestParam(required = false) String module,
                                                          @RequestParam(required = false) Long id,
                                                          @RequestParam(required = false) String dept,
                                                           @RequestParam(required = false) String county_code,
                                                          @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                                          @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 部门代号 → 部门账号前缀（operator LIKE 'ADMIN-'）与归属 target 匹配；county_code 可选按区县进一步过滤（56部门口径）
        String scopeTarget = dept == null || dept.isBlank() ? null : dept;
        PageData<Map<String, Object>> data =
                auditLogService.deptPage(log_type, module, id, scopeTarget, county_code, start_time, end_time, page, pageSize, appealKeyword);
        return Result.page(data);
    }
}
