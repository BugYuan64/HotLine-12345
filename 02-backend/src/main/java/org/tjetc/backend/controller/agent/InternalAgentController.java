package org.tjetc.backend.controller.agent;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.util.MaskUtil;
import org.tjetc.backend.service.admin.AccountAuditService;
import org.tjetc.backend.service.admin.AppealManageService;
import org.tjetc.backend.service.admin.AuditLogService;
import org.tjetc.backend.service.admin.OrderService;
import org.tjetc.backend.service.admin.PredictionService;
import org.tjetc.backend.service.admin.TodoSummaryService;
import org.tjetc.backend.service.admin.UserAccountService;
import org.tjetc.backend.service.admin.WarningService;
import org.tjetc.backend.service.common.AgentContextService;
import org.tjetc.backend.service.dept.DeptOrderService;
import org.tjetc.backend.service.staff.StaffWarningService;
import org.tjetc.backend.service.street.StreetOrderService;
import org.tjetc.backend.service.admin.OrderLogService;
import org.tjetc.backend.service.citizen.PublicAppealService;
import org.tjetc.backend.service.citizen.PublicFeedbackService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 助手内网工具端点（四端 AI 助手工具调用回查业务数据）：
 * - 仅供 Python AI 服务内网调用：校验 X-Timestamp/X-Internal-Sign（内网签名）+ X-Agent-Context（用户上下文令牌）；
 * - 不再走 JWT（/internal/** 不在 AuthInterceptor 拦截范围），用户身份由上下文令牌还原为 LoginUser，
 *   数据权限仍由各端既有 service 按 LoginUser 过滤（市民本人 / 部门本分类 / 街道本辖区 / 管理员全量）；
 * - 本控制器仅查询/触发，不直接改业务表；触发类（预警扫描/预判）由既有 service 落库并审计。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/internal/agent")
public class InternalAgentController {

    private final AgentContextService agentContextService;

    // ------------- 市民端 -------------
    private final PublicAppealService publicAppealService;
    private final PublicFeedbackService publicFeedbackService;

    // ------------- 街道办端 -------------
    private final StreetOrderService streetOrderService;
    private final StaffWarningService staffWarningService;
    private final OrderLogService orderLogService;

    // ------------- 部门端 -------------
    private final DeptOrderService deptOrderService;

    // ------------- 管理端 -------------
    private final OrderService orderService;
    private final AppealManageService appealManageService;
    private final AuditLogService auditLogService;
    private final WarningService warningService;
    private final PredictionService predictionService;
    private final UserAccountService userAccountService;
    private final AccountAuditService accountAuditService;
    private final TodoSummaryService todoSummaryService;
    private final StringRedisTemplate redis;

    /** 触发类防重入：同一时刻只允许一个预判 / 一次预警扫描，避免模型重复触发造成重复执行 */
    private final java.util.concurrent.atomic.AtomicBoolean predictionRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean scanRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ==================================================================
    //  通用鉴权：内网签名 + 上下文令牌 + 端类型匹配
    // ==================================================================

    /**
     * 校验内网签名与上下文令牌并还原 LoginUser。
     * 端类型匹配：citizen→市民、street→街道办、admin→管理员，staff→其余内部部门账号。
     */
    private LoginUser requireContext(HttpServletRequest request, String scope) {
        agentContextService.verifyInternalSign(
                request.getHeader("X-Timestamp"), request.getHeader("X-Internal-Sign"));
        LoginUser user = agentContextService.verify(request.getHeader("X-Agent-Context"));
        String ut = user.userType() == null ? "" : user.userType();
        boolean ok = switch (scope) {
            case "citizen" -> "CITIZEN".equals(ut);
            case "street" -> "STREET".equals(ut);
            case "admin" -> "ADMIN".equals(ut);
            default -> !ut.isBlank()
                    && !"CITIZEN".equals(ut) && !"STREET".equals(ut) && !"ADMIN".equals(ut);
        };
        if (!ok) {
            throw new BusinessException(ResultCode.FORBIDDEN, "AI 助手上下文与请求端不匹配");
        }
        return user;
    }

    private static int page(int p) {
        return Math.max(1, p);
    }

    private static int pageSize(int ps) {
        return Math.max(1, Math.min(50, ps));
    }

    // ==================================================================
    //  市民端：我的诉求 / 办理进度
    // ==================================================================

    /** 我的诉求列表（本人） */
    @GetMapping("/citizen/appeals")
    public Result<Map<String, Object>> citizenAppeals(HttpServletRequest request,
                                                      @RequestParam(required = false) String appeal_no,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        LoginUser user = requireContext(request, "citizen");
        // 服务层返回 Map（含 bizStatus/bizText/children/departments 等聚合字段），非 AppealParent 实体
        PageData<Map<String, Object>> data = publicAppealService.listByCurrentUser(user, page(page), pageSize(pageSize), appeal_no);
        List<Map<String, Object>> list = data.list().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.get("id"));
            m.put("appeal_no", a.get("appealNo"));
            m.put("title", a.get("title"));
            m.put("status", a.get("bizStatus"));
            m.put("biz_text", a.get("bizText"));
            m.put("category", a.get("category"));
            m.put("region", a.get("district"));
            m.put("children", a.get("children"));
            m.put("departments", a.get("departments"));
            m.put("create_time", a.get("createTime"));
            return m;
        }).toList();
        return Result.page(PageData.of(list, data.total(), data.page(), data.page_size()));
    }

    /** 某诉求办理进度与流转时间线（本人归属校验由 feedbackService 完成） */
    @GetMapping("/citizen/appeals/{appealNo}/progress")
    public Result<Map<String, Object>> citizenAppealProgress(HttpServletRequest request,
                                                              @PathVariable String appealNo) {
        LoginUser user = requireContext(request, "citizen");
        return Result.ok(publicFeedbackService.timeline(appealNo, null, user));
    }

    // ==================================================================
    //  街道办端：本辖区工单 / 分发到本街道的预警
    // ==================================================================

    /** 本街道工单分页（region 权限内） */
    @GetMapping("/street/orders")
    public Result<Map<String, Object>> streetOrders(HttpServletRequest request,
                                                    @RequestParam(required = false) String order_no,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                                    @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        LoginUser user = requireContext(request, "street");
        PageData<Map<String, Object>> data = streetOrderService.page(
                order_no, keyword, status, start_time, end_time, page(page), pageSize(pageSize), user);
        return Result.page(data);
    }

    /** 工单详情（含流转时间线，region 权限内） */
    @GetMapping("/street/orders/{id}")
    public Result<Map<String, Object>> streetOrderDetail(HttpServletRequest request, @PathVariable Long id) {
        LoginUser user = requireContext(request, "street");
        return Result.ok(streetOrderService.detail(id, user));
    }

    /** 工单流转时间线（region 权限内） */
    @GetMapping("/street/orders/{id}/timeline")
    public Result<Map<String, Object>> streetOrderTimeline(HttpServletRequest request, @PathVariable Long id) {
        LoginUser user = requireContext(request, "street");
        streetOrderService.requireScope(id, user);
        return Result.ok(Map.of("timeline", orderLogService.timeline(id, OrderLogService.TimelineEnd.STREET)));
    }

    /** 分发到本街道的预警记录（region 隔离，响应状态闭环） */
    @GetMapping("/street/warnings")
    public Result<Map<String, Object>> streetWarnings(HttpServletRequest request,
                                                      @RequestParam(required = false) String sub_category,
                                                      @RequestParam(required = false) String warning_level,
                                                      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                                      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        LoginUser user = requireContext(request, "street");
        PageData<Map<String, Object>> data = staffWarningService.page(
                sub_category, warning_level, null, start_time, end_time, page(page), pageSize(pageSize), user);
        return Result.page(data);
    }

    // ==================================================================
    //  部门端：本部门工单 / 工作台
    // ==================================================================

    /** 本部门工单分页（user_type=分类权限内，可再按街道前缀过滤） */
    @GetMapping("/staff/orders")
    public Result<Map<String, Object>> staffOrders(HttpServletRequest request,
                                                   @RequestParam(required = false) String order_no,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String street_office,
                                                   @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                                   @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        LoginUser user = requireContext(request, "staff");
        PageData<Map<String, Object>> data = deptOrderService.page(
                order_no, keyword, status, null, street_office, start_time, end_time, page(page), pageSize(pageSize), user);
        return Result.page(data);
    }

    /** 工单详情（部门权限内） */
    @GetMapping("/staff/orders/{id}")
    public Result<Map<String, Object>> staffOrderDetail(HttpServletRequest request, @PathVariable Long id) {
        LoginUser user = requireContext(request, "staff");
        return Result.ok(deptOrderService.detail(id, user));
    }

    /** 工单流转时间线（部门权限内） */
    @GetMapping("/staff/orders/{id}/timeline")
    public Result<Map<String, Object>> staffOrderTimeline(HttpServletRequest request, @PathVariable Long id) {
        LoginUser user = requireContext(request, "staff");
        deptOrderService.requireScope(id, user);
        return Result.ok(Map.of("timeline", orderLogService.timeline(id, OrderLogService.TimelineEnd.DEPT)));
    }

    /** 部门工作台汇总（待签收/办理中/超时等） */
    @GetMapping("/staff/workbench")
    public Result<Map<String, Object>> staffWorkbench(HttpServletRequest request) {
        LoginUser user = requireContext(request, "staff");
        return Result.ok(deptOrderService.workbenchSummary(user));
    }

    // ==================================================================
    //  管理端：全量数据 + 触发类
    // ==================================================================

    /** 全平台工单分页 */
    @GetMapping("/admin/orders")
    public Result<Map<String, Object>> adminOrders(HttpServletRequest request,
                                                   @RequestParam(required = false) String order_no,
                                                   @RequestParam(required = false) String category,
                                                   @RequestParam(required = false) String level,
                                                   @RequestParam(required = false) String region,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String start_date,
                                                   @RequestParam(required = false) Integer archived,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        requireContext(request, "admin");
        PageData<Map<String, Object>> data = orderService.listOrders(
                order_no, category, level, region, status, keyword, start_date, null, null,
                archived, page(page), pageSize(pageSize));
        return Result.page(data);
    }

    /** 工单全链路视图 */
    @GetMapping("/admin/orders/{id}/full-chain")
    public Result<Map<String, Object>> adminOrderFullChain(HttpServletRequest request, @PathVariable Long id) {
        requireContext(request, "admin");
        return Result.ok(orderService.fullChain(id));
    }

    /** 诉求分页（父/子，scope=parent|child） */
    @GetMapping("/admin/appeals")
    public Result<Map<String, Object>> adminAppeals(HttpServletRequest request,
                                                    @RequestParam(required = false) String appeal_no,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(defaultValue = "parent") String scope,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        requireContext(request, "admin");
        PageData<Map<String, Object>> data = appealManageService.page(
                appeal_no, status, keyword, scope, page(page), pageSize(pageSize));
        return Result.page(data);
    }

    /** 父诉求 → 子诉求明细（含关联工单），最多附带前 20 个子诉求的工单信息 */
    @GetMapping("/admin/appeals/{parentId}/children")
    public Result<Map<String, Object>> adminAppealChildren(HttpServletRequest request, @PathVariable Long parentId) {
        requireContext(request, "admin");
        List<Map<String, Object>> children = appealManageService.children(parentId);
        List<Map<String, Object>> slim = new ArrayList<>();
        for (Map<String, Object> c : children) {
            Map<String, Object> m = new LinkedHashMap<>(c);
            // PII 收敛：不把原始市民手机号喂给 AI 模型（姓名保留，供管理员识别）
            m.put("citizen_phone", MaskUtil.maskPhone((String) c.get("citizen_phone")));
            Object childId = c.get("id");
            if (childId != null && slim.size() < 20) {
                m.put("order", appealManageService.getChildOrder(Long.valueOf(String.valueOf(childId))));
            }
            slim.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parent_id", parentId);
        data.put("children", slim);
        data.put("count", slim.size());
        return Result.ok(data);
    }

    /** 审计/操作日志统一查询：view=system|dept|street */
    @GetMapping("/admin/logs")
    public Result<Map<String, Object>> adminLogs(HttpServletRequest request,
                                                 @RequestParam(defaultValue = "system") String view,
                                                 @RequestParam(required = false) String log_type,
                                                 @RequestParam(required = false) String operator,
                                                 @RequestParam(required = false) String module,
                                                 @RequestParam(required = false) String dept,
                                                 @RequestParam(required = false) String county,
                                                 @RequestParam(required = false) String appeal_keyword,
                                                 @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                                 @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        requireContext(request, "admin");
        PageData<Map<String, Object>> data;
        switch (view == null ? "system" : view) {
            case "dept" -> data = auditLogService.deptPage(
                    log_type, module, null, dept, null /*countyCode：AI助手按部门查询不过滤区县*/, start_time, end_time, page(page), pageSize(pageSize), appeal_keyword);
            case "street" -> data = auditLogService.streetPage(
                    log_type, module, null, county, start_time, end_time, page(page), pageSize(pageSize), appeal_keyword);
            default -> data = auditLogService.page(
                    log_type, operator, module, null, start_time, end_time, page(page), pageSize(pageSize),
                    "ADMIN", null, appeal_keyword);
        }
        return Result.page(data);
    }

    /** 预警记录分页 */
    @GetMapping("/admin/warnings")
    public Result<Map<String, Object>> adminWarnings(HttpServletRequest request,
                                                     @RequestParam(required = false) String warning_level,
                                                     @RequestParam(required = false) String category,
                                                     @RequestParam(required = false) String region,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start_time,
                                                     @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end_time,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        requireContext(request, "admin");
        PageData<Map<String, Object>> data = warningService.pageRecords(
                warning_level, category, region, status, start_time, end_time, page(page), pageSize(pageSize));
        return Result.page(data);
    }

    /** 预警规则列表 */
    @GetMapping("/admin/warning-rules")
    public Result<Map<String, Object>> adminWarningRules(HttpServletRequest request) {
        requireContext(request, "admin");
        return Result.ok(Map.of("rules", warningService.rules()));
    }

    /** 预判数据：part=hotspots|trend|accuracy */
    @GetMapping("/admin/prediction")
    public Result<Map<String, Object>> adminPrediction(HttpServletRequest request,
                                                       @RequestParam(defaultValue = "hotspots") String part,
                                                       @RequestParam(required = false) String category,
                                                       @RequestParam(required = false) String sub_category,
                                                       @RequestParam(required = false) String region,
                                                       @RequestParam(required = false) String period) {
        requireContext(request, "admin");
        return Result.ok(switch (part == null ? "hotspots" : part) {
            case "trend" -> predictionService.trend(period);
            case "accuracy" -> predictionService.accuracy();
            default -> predictionService.query(category, sub_category, region, period);
        });
    }

    /** 账号分页 */
    @GetMapping("/admin/users")
    public Result<Map<String, Object>> adminUsers(HttpServletRequest request,
                                                  @RequestParam(required = false) String name,
                                                  @RequestParam(required = false) String job_no,
                                                  @RequestParam(required = false) String user_type,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        requireContext(request, "admin");
        PageData<Map<String, Object>> data = userAccountService.page(
                name, job_no, user_type, status, page(page), pageSize(pageSize));
        return Result.page(data);
    }

    /** 待审核账号列表 */
    @GetMapping("/admin/pending-accounts")
    public Result<Map<String, Object>> adminPendingAccounts(HttpServletRequest request,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) String name,
                                                            @RequestParam(required = false) String job_no,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        requireContext(request, "admin");
        PageData<Map<String, Object>> data = accountAuditService.page(status, name, job_no, page(page), pageSize(pageSize));
        return Result.page(data);
    }

    /** 全局待办汇总 */
    @GetMapping("/admin/todo-summary")
    public Result<Map<String, Object>> adminTodoSummary(HttpServletRequest request) {
        requireContext(request, "admin");
        return Result.ok(todoSummaryService.summary());
    }

    /** 系统自动处理开关状态（拆分/建单/自动派单），后期扩展为 AI 可操作开关时在同一端点加写操作 */
    @GetMapping("/admin/switches")
    public Result<Map<String, Object>> adminSwitches(HttpServletRequest request) {
        requireContext(request, "admin");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auto_split_parents", on("config:auto_split_parents"));
        m.put("auto_create_orders", on("config:auto_create_orders"));
        m.put("auto_dispatch", on("config:auto_dispatch"));
        m.put("auto_split_operator", redis.opsForValue().get("config:auto_split_operator"));
        m.put("auto_order_operator", redis.opsForValue().get("config:auto_order_operator"));
        m.put("auto_dispatch_operator", redis.opsForValue().get("config:auto_dispatch_operator"));
        m.put("remind", "开关状态为实时 Redis 配置，供查询与告知；AI 操作开关功能后续版本开放");
        return Result.ok(m);
    }

    private boolean on(String key) {
        return "true".equalsIgnoreCase(redis.opsForValue().get(key));
    }

    /** AI 助手触发预警扫描（复用既有 runWarningScan，落库并审计；防重入） */
    @PostMapping("/admin/warning-scan")
    public Result<Map<String, Object>> adminTriggerWarningScan(HttpServletRequest request) {
        requireContext(request, "admin");
        if (!scanRunning.compareAndSet(false, true)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "预警扫描正在执行中，请稍后再试");
        }
        try {
            int triggered = warningService.runWarningScan();
            auditLogService.recordOperation("ADMIN", "warning-scan", "AI助手触发预警扫描：触发" + triggered + "条");
            return Result.ok(Map.of("triggered", triggered));
        } finally {
            scanRunning.set(false);
        }
    }

    /** AI 助手触发预判（复用既有 runPrediction，含统计桥刷新+AI 审计；防重入） */
    @PostMapping("/admin/predict")
    public Result<Map<String, Object>> adminTriggerPrediction(HttpServletRequest request) {
        requireContext(request, "admin");
        if (!predictionRunning.compareAndSet(false, true)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "预判正在执行中，请稍后再试");
        }
        try {
            int rows = predictionService.runPrediction();
            return Result.ok(Map.of("predicted_hotspots", Math.max(0, rows)));
        } finally {
            predictionRunning.set(false);
        }
    }
}
