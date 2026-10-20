package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.util.ExcelUtil;
import org.tjetc.backend.util.MaskUtil;
import org.tjetc.backend.dto.OrderClassifyCorrectRequest;
import org.tjetc.backend.dto.OrderCreateRequest;
import org.tjetc.backend.dto.OrderUpdateRequest;
import org.tjetc.backend.dto.staff.BatchAuditRequest;
import org.tjetc.backend.dto.admin.BatchFinalRequest;
import org.tjetc.backend.dto.staff.DelayApproveRequest;
import org.tjetc.backend.dto.staff.DisposeRequest;
import org.tjetc.backend.dto.admin.FinalRejectRequest;
import org.tjetc.backend.dto.admin.OrderDispatchRequest;
import org.tjetc.backend.dto.admin.ReopenApproveRequest;
import org.tjetc.backend.dto.staff.ScoreRequest;
import org.tjetc.backend.dto.staff.VerifyRequest;
import org.tjetc.backend.dto.ai.AiClassifyResult;
import org.tjetc.backend.dto.ai.AiDedupResult;
import org.tjetc.backend.service.citizen.PublicAppealService;
import org.tjetc.backend.service.common.AiServiceClient;
import org.tjetc.backend.entity.citizen.AppealChild;
import org.tjetc.backend.entity.staff.OrderDelayApply;
import org.tjetc.backend.entity.staff.OrderDisposeAttachment;
import org.tjetc.backend.entity.citizen.OrderEvaluate;
import org.tjetc.backend.entity.common.SysMessage;
import org.tjetc.backend.entity.common.SysRegion;
import org.tjetc.backend.entity.common.WorkOrder;
import org.tjetc.backend.entity.common.WorkOrderAppeal;
import org.tjetc.backend.entity.admin.ClassifyPrimary;
import org.tjetc.backend.mapper.admin.ClassifyPrimaryMapper;
import org.tjetc.backend.mapper.citizen.AppealChildMapper;
import org.tjetc.backend.mapper.staff.OrderDelayApplyMapper;
import org.tjetc.backend.mapper.staff.OrderDisposeAttachmentMapper;
import org.tjetc.backend.mapper.citizen.OrderEvaluateMapper;
import org.tjetc.backend.mapper.common.SysFileMapper;
import org.tjetc.backend.mapper.common.SysMessageMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.tjetc.backend.mapper.common.SysUserMapper;
import org.tjetc.backend.mapper.admin.AdminOrderArchiveMapper;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;
import org.tjetc.backend.mapper.dept.DeptWorkOrderMapper;
import org.tjetc.backend.mapper.street.StreetWorkOrderMapper;
import org.tjetc.backend.service.common.OrderCopySyncService;
import org.tjetc.backend.mapper.common.WorkOrderAppealMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.tjetc.backend.service.admin.FileService;
import org.tjetc.backend.service.admin.SseEventService;
import org.tjetc.backend.service.common.RegionService;

/**
 * 工单分派与流转闭环（需求 12/21/22/23/25 / API 6.10/9.2~9.5/11 章）——状态机唯一权威实现：
 * 正常流转：PENDING_DISPATCH → PENDING_DEPT_PROCESS → PENDING_DEPT_ASSIGN → PENDING_STREET_PROCESS
 *           → STREET_DONE → CLOSED（已办结，终审确认由管理端执行）→ 归档(is_archived=1) / EVALUATED
 * 异常分支：RETURNED（部门退回重分）；每次状态变更必须写 order_log，禁止直接 UPDATE status
 * 硬编码上限（需求 25(2)）：部门退回街道办≤2次（第3次强制升级admin协调）、延期≤2次每次≤3个工作日、
 * Admin终审退回≤1次（退回后必须重新走完整流程）、工单重开≤1次
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    /** 工单状态（方案2：全新状态机，三端协同流转） */
    public static final String STATUS_PENDING_DISPATCH = "PENDING_DISPATCH";           // 待管理端分派（工单初始）
    public static final String STATUS_PENDING_DEPT_PROCESS = "PENDING_DEPT_PROCESS";   // 待部门端处理（部门端=待审核）
    public static final String STATUS_PENDING_DEPT_ASSIGN = "PENDING_DEPT_ASSIGN";     // 待分派（部门端确认后）
    public static final String STATUS_PENDING_STREET_PROCESS = "PENDING_STREET_PROCESS"; // 待街道办处理（街道办=待处理）
    public static final String STATUS_STREET_DONE = "STREET_DONE";                     // 街道办已处理（部门端=待审核，存量兼容）
    public static final String STATUS_PENDING_EVALUATE = "PENDING_EVALUATE";           // 待市民评价（街道办处置完成后进入，部门端=待评价）
    public static final String STATUS_STREET_REJECTED = "STREET_REJECTED";               // 街道办已驳回（部门端=待处理）
    public static final String STATUS_DEPT_REJECTED = "DEPT_REJECTED";                   // 部门已驳回（管理端=待终审）
    public static final String STATUS_DEPT_AUDITED = "DEPT_AUDITED";                       // 部门已审核（管理端=待终审确认）
    public static final String STATUS_PENDING_REASSIGN = "PENDING_REASSIGN";               // 待重新分派（部门驳回后回管理端）

    /** 人员归属类型 → 部门（API 14.1 user_type）：8 大分派部门 + 街道办 */
    private static final Map<String, String> DEPT_BY_USER_TYPE = Map.ofEntries(
            Map.entry("URBAN", "城管部门"),
            Map.entry("TRAFFIC", "交通部门"),
            Map.entry("MARKET", "市场监管部门"),
            Map.entry("ENVIRONMENT", "生态环境部门"),
            Map.entry("HOUSING", "住建部门"),
            Map.entry("LABOR", "人社部门"),
            Map.entry("PUBLIC", "市政部门"),
            Map.entry("HEALTH", "卫健部门"),
            Map.entry("PROPERTY", "物业部门"),
            Map.entry("LIVELIHOOD", "民生部门"));
    /** 一级分类白名单：8 大类 + OTHER（建单防呆：历史遗留 PROPERTY/LIVELIHOOD 等归一为 OTHER） */
    private static final java.util.Set<String> VALID_PRIMARY_CATEGORIES = java.util.Set.of(
            "URBAN", "TRAFFIC", "MARKET", "ENVIRONMENT",
            "HOUSING", "LABOR", "PUBLIC", "HEALTH", "OTHER");
    /** 部门退回街道办上限：第3次强制升级 admin 协调（需求 25(2)） */
    private static final int MAX_DEPT_RETURN = 2;
    /** 延期上限2次、每次≤3个工作日（需求 25(2)） */
    private static final int MAX_DELAY_TIMES = 2;
    private static final int MAX_DELAY_DAYS = 3;
    /** 工单重开上限1次（需求 25(2)） */
    private static final int MAX_REOPEN_TIMES = 1;
    /** 办结自动归档天数（需求 23(5)：办结后7天） */
    private static final int AUTO_ARCHIVE_DAYS = 7;
    /** 市民评价窗口天数（处置完成(dispose_time)起算，与 PublicFeedbackService.EVALUATE_WINDOW_DAYS 一致） */
    private static final int EVALUATE_WINDOW_DAYS = 7;

    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final AdminOrderArchiveMapper adminOrderArchiveMapper;
    private final DeptWorkOrderMapper deptWorkOrderMapper;
    private final StreetWorkOrderMapper streetWorkOrderMapper;
    private final OrderCopySyncService orderCopySyncService;
    private final WorkOrderAppealMapper workOrderAppealMapper;
    private final OrderLogService orderLogService;
    private final OrderDelayApplyMapper delayApplyMapper;
    private final OrderDisposeAttachmentMapper disposeAttachmentMapper;
    private final OrderEvaluateMapper evaluateMapper;
    private final AppealChildMapper appealChildMapper;
    private final SysFileMapper sysFileMapper;
    private final FileService fileService;
    private final SysMessageMapper messageMapper;
    private final SysUserMapper sysUserMapper;
    private final RegionService regionService;
    private final StringRedisTemplate redisTemplate;
    private final AuditLogService auditLogService;
    private final SseEventService sseEventService;
    private final AiServiceClient aiServiceClient;
    private final ClassifyPrimaryMapper classifyPrimaryMapper;

    /** 去重归并窗口（天） */
    @Value("${app.order.dedup.window-days:7}")
    private int dedupWindowDays;
    /** AI 相似度归并阈值 */
    @Value("${app.order.dedup.similarity-threshold:0.85}")
    private double dedupThreshold;
    /** 是否启用 AI 相似度兜底 */
    @Value("${app.order.dedup.enable-ai-dedup:true}")
    private boolean enableAiDedup;
    /** 等级判定阈值：关注度 >= secondMin 且 < firstMin 为二级，>= firstMin 为一级 */
    @Value("${app.order.level.second-min-count:3}")
    private int levelSecondMin;
    @Value("${app.order.level.first-min-count:5}")
    private int levelFirstMin;

    // ============================================================ 通用工具

    /** 工单查询（逻辑删除过滤 + 不存在异常）：管理端权威副本（admin_work_order），管理端/市民/系统路径使用 */
    public WorkOrder requireOrder(Long id) {
        WorkOrder order = adminWorkOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "工单不存在");
        }
        return order;
    }

    /** 部门端副本加载（三端数据解耦读取边界）：工单未分派至部门（副本不存在）即不可见、不可操作 */
    public WorkOrder requireDeptOrder(Long id) {
        WorkOrder order = deptWorkOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "工单不存在或未分派至本部门");
        }
        return order;
    }

    /** 街道办端副本加载（三端数据解耦读取边界）：工单未分派至本辖区（副本不存在）即不可见、不可操作 */
    public WorkOrder requireStreetOrder(Long id) {
        WorkOrder order = streetWorkOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "工单不存在或未分派至本辖区");
        }
        return order;
    }

    /** 一级分类 → 分派部门（从 classify_primary 表动态读取，不再硬编码） */
    private String deptOf(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        ClassifyPrimary p = classifyPrimaryMapper.selectByCode(category);
        return p == null ? null : p.getDepartment();
    }

    /** 分派部门展示名：区县+部门拼接（如「海港区城管部门」，56=7区县×8分类口径）；
     *  工单无区县信息时回退纯部门名（历史数据兼容） */
    private String deptDisplay(WorkOrder order, String category) {
        String dept = deptOf(category);
        if (dept == null) {
            return null;
        }
        String county = normalizeCounty(order.getCountyName() != null ? order.getCountyName() : order.getRegion());
        return county == null ? dept : county + dept;
    }

    /**
     * 手动指定分派部门（详情弹窗「处理部门」下拉）：department 为「区县+部门名」全称（如「海港区城管部门」）。
     * 解析区县前缀回填工单区县（county_name/county_code），并按部门名反查 classify_primary 同步一级分类，
     * 保证部门端「分类+区县」隔离可见口径一致；空串表示清空分派部门（回退待人工指定）。
     */
    private void applyDepartment(WorkOrder order, String department) {
        if (department == null || department.isBlank()) {
            order.setDepartment(null);
            return;
        }
        String county = normalizeCounty(department);
        if (county != null) {
            order.setCountyName(county);
            order.setCountyCode(regionService.resolveCountyCode(county));
            String deptName = department.substring(county.length());
            // 部门名反查一级分类（部门端按 category+county_code 隔离可见，必须与所选部门保持一致）
            classifyPrimaryMapper.selectEnabled().stream()
                    .filter(p -> deptName.equals(p.getDepartment()))
                    .findFirst()
                    .ifPresent(p -> order.setCategory(p.getCode()));
        }
        order.setDepartment(department);
    }

    /** 部门「收到工单」审计归属：部门代号+区县编码后缀（56部门口径，如 URBAN:130302；无区县时保持纯代号兼容历史） */
    private String deptReceiveTarget(WorkOrder order) {
        String t = order.getCategory();
        return order.getCountyCode() == null || order.getCountyCode().isBlank() ? t : t + ":" + order.getCountyCode();
    }

    /** 归档后只读校验（需求 23(5)） */
    private void requireNotArchived(WorkOrder order) {
        if (order.getIsArchived() != null && order.getIsArchived() == 1) {
            throw new BusinessException(ResultCode.FORBIDDEN, "工单已归档，只读不可修改");
        }
    }

    /** 街道办人员校验：工单承办街道办必须与本人辖区一致（API 2.5 越权防护）
     *  street_office 为纯街道名（如「文化路街道」），账号 region 为街道名：兼容历史带「街道办」后缀数据（去后缀后比较） */
    private void requireStreetScope(WorkOrder order, LoginUser user) {
        if (!"STREET".equals(user.userType())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅街道办人员可执行该操作");
        }
        String office = order.getStreetOffice();
        String officeRegion = office != null && office.endsWith("街道办")
                ? office.substring(0, office.length() - 3) : office;
        if (officeRegion == null || !officeRegion.equals(user.region())) {
            throw new BusinessException(ResultCode.REGION_FORBIDDEN, "越权访问他人辖区工单数据");
        }
    }

    /** 部门人员校验：工单分类与区县必须与本人一致（API 2.5 越权防护；56=7区县×8分类口径） */
    private void requireDeptScope(WorkOrder order, LoginUser user) {
        // 以工单 category（= 一级分类 code = user_type）判断归属，兼容旧数据 department 字段不一致；
        // 区县按 county_code 隔离（部门账号 region_code=区县级编码；账号无区县时不校验，兼容过渡期）
        if (order.getCategory() == null || !order.getCategory().equals(user.userType())) {
            throw new BusinessException(ResultCode.REGION_FORBIDDEN, "越权访问其他部门工单数据");
        }
        if (user.regionCode() != null && !user.regionCode().isBlank()
                && !user.regionCode().equals(order.getCountyCode())) {
            throw new BusinessException(ResultCode.REGION_FORBIDDEN, "越权访问其他区县部门工单数据");
        }
    }

    /** 分派至街道办时自动计算处理截止时间（需求 21(6)：一级/二级紧急2个工作日，三级普通5个工作日） */
    private LocalDateTime calcDeadline(String level, LocalDateTime base) {
        int days = "三级".equals(level) ? 5 : 2;
        return addWorkdays(base, days);
    }

    /** 自然日加 N 个工作日（跳过周末，需求 21(6) 时效计算） */
    private LocalDateTime addWorkdays(LocalDateTime base, int days) {
        LocalDateTime t = base;
        int added = 0;
        while (added < days) {
            t = t.plusDays(1);
            DayOfWeek d = t.getDayOfWeek();
            if (d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return t;
    }

    /** ISO 8601 日期时间解析（如 2026-08-21T15:00:00+08:00） */
    private LocalDateTime parseIso(String s) {
        try {
            return OffsetDateTime.parse(s).toLocalDateTime();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e2) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "时间格式不合法：" + s);
            }
        }
    }

    /** 站内消息推送（API 4.6-4.8 消息类型：ORDER_DISPATCHED/ORDER_RETURNED/ORDER_CLOSED/ORDER_REOPENED） */
    private void pushMessage(Long userId, String phone, String msgType, String title, String content) {
        SysMessage message = new SysMessage();
        message.setUserId(userId);
        message.setPhone(phone);
        message.setMsgType(msgType);
        message.setTitle(title);
        message.setContent(content);
        message.setIsRead(0);
        message.setChannel("IN_APP");
        messageMapper.insert(message);
    }

    // ============================================================ 9.2 部门筛选核验

    /**
     * 部门筛选核验（API 9.2）：CONFIRM 确认归属（留痕，随后可分派街道办）；RETURN 退回重分
     */
    @Transactional
    public Map<String, Object> verify(Long id, VerifyRequest request, LoginUser user) {
        WorkOrder order = requireDeptOrder(id);
        requireNotArchived(order);
        requireDeptScope(order, user);
        if (!"PENDING_DEPT_ASSIGN".equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可核验");
        }
        String before = order.getStatus();
        if ("RETURN".equals(request.action())) {
            if (request.returnReason() == null || request.returnReason().isBlank()) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "退回原因不能为空");
            }
            // bug修复：原置 RETURNED 为流转死端（分派入口不接收该状态）→ 统一回「待重新分派」并清空部门，
            // 由副本不变式自动删除部门端副本（工单回管理端重新分派池）
            order.setStatus(STATUS_PENDING_REASSIGN);
            order.setDepartment(null);
            orderCopySyncService.sync(order);
            orderLogService.record(id, "RETURN", user.userId(), user.userType(),
                    "部门退回重分：" + request.returnReason(), before, order.getStatus());
            pushMessage(null, null, "ORDER_RETURNED", "工单退回重分",
                    "工单 " + order.getOrderNo() + " 被退回重分：" + request.returnReason());
            auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-verify",
                    "部门退回重分：" + order.getOrderNo() + "（" + request.returnReason() + "）");
            return Map.of("status", order.getStatus());
        }
        // CONFIRM：核验通过留痕（状态不变，随后 9.3 分派街道办）
        orderLogService.record(id, "VERIFY", user.userId(), user.userType(),
                "部门核验通过，确认工单归属", before, before);
        return Map.of("status", before);
    }

    // ============================================================ 部门端申请驳回（回管理端重新分派）

    /**
     * 部门端申请驳回：待部门端处理 → 待重新分派（部门认为不属本部门/无法处理，退回管理端重新分派）
     */
    @Transactional
    public Map<String, Object> deptApplyReject(Long id, String rejectReason, LoginUser user) {
        WorkOrder order = requireDeptOrder(id);
        requireNotArchived(order);
        requireDeptScope(order, user);
        if (!STATUS_PENDING_DEPT_PROCESS.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可申请驳回");
        }
        if (rejectReason == null || rejectReason.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "驳回原因不能为空");
        }
        String before = order.getStatus();
        order.setStatus(STATUS_PENDING_REASSIGN);
        order.setDepartment(null);
        orderCopySyncService.sync(order);
        orderLogService.record(id, "DEPT_APPLY_REJECT", user.userId(), user.userType(),
                "部门申请驳回：" + rejectReason, before, order.getStatus());
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-reject",
                "部门申请驳回工单：" + order.getOrderNo() + "（" + rejectReason + "）");
        return Map.of("status", order.getStatus());
    }

    // ============================================================ 9.3 分派至街道办

    /**
     * 分派至街道办（API 9.3）：缺省按工单行政区划自动匹配；分派后计算处理截止时间并通知街道办
     */
    @Transactional
    public Map<String, Object> assignStreet(Long id, String streetOffice, String remark, LoginUser user) {
        WorkOrder order = requireDeptOrder(id);
        requireNotArchived(order);
        requireDeptScope(order, user);
        // 待部门端处理的工单允许直接分派：自动完成部门确认（状态→待分派）后继续分派
        if (STATUS_PENDING_DEPT_PROCESS.equals(order.getStatus())) {
            orderLogService.record(id, "CONFIRM", user.userId(), user.userType(),
                    "部门确认（随分派自动完成）", order.getStatus(), STATUS_PENDING_DEPT_ASSIGN);
            order.setStatus(STATUS_PENDING_DEPT_ASSIGN);
        }
        if (!"PENDING_DEPT_ASSIGN".equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可分派");
        }
        String before = STATUS_PENDING_DEPT_ASSIGN;
        // 承办街道办值统一为纯街道名（无后缀）：显式参数已为该格式；留空时按工单区县自动匹配（county_name 优先，缺失回退 region）；
        // 二级分类为"其他"时不自动匹配（无对应街道办，需人工手动选择后方可分派）
        String target = streetOffice;
        if (target == null || target.isBlank()) {
            if (!"其他".equals(order.getSubCategory())) {
                target = matchStreetOffice(order.getRegion());
            }
        }
        if (target == null || target.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "其他".equals(order.getSubCategory())
                            ? "二级分类为「其他」，请手动指定承办街道办"
                            : "工单缺少行政区划，请手动指定承办街道办");
        }
        order.setStreetOffice(target);
        order.setStatus(STATUS_PENDING_STREET_PROCESS);
        // 记录工单到达街道办时间（街道办端未读红点基准）
        order.setStreetReceiveTime(LocalDateTime.now());
        // 处理截止时间（需求 21(6)：根据工单等级自动计算）
        order.setDeadline(calcDeadline(order.getLevel(), LocalDateTime.now()));
        orderCopySyncService.sync(order);
        orderLogService.record(id, "ASSIGN_STREET", user.userId(), user.userType(),
                "分派至街道办：" + target + (remark == null || remark.isBlank() ? "" : "（说明：" + remark + "）"), before, order.getStatus());
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-dispatch",
                "部门分派街道办：" + order.getOrderNo() + " → " + target);
        // 街道办收到工单日志（操作人=接收方前缀 STREET，归属=STREET:区县名（不带后缀），与街道办账号 region 口径一致，仅本区县街道办可见）
        String streetCounty = target != null && target.endsWith("街道办")
                ? target.substring(0, target.length() - 3) : target;
        auditLogService.recordOperation("STREET", "order-receive",
                "收到工单：" + order.getOrderNo() + "（来自部门端 " + user.jobNo() + "）",
                "STREET:" + streetCounty);
        // 通知街道办（需求 15(2) 街道办收到工单效果展示）
        List<Map<String, Object>> streetUsers = listStreetStaff(target);
        for (Map<String, Object> u : streetUsers) {
            pushMessage(Long.valueOf(String.valueOf(u.get("id"))), null, "ORDER_DISPATCHED",
                    "新工单待处理", "工单 " + order.getOrderNo() + "（" + order.getTitle() + "）已分派至你辖区，请及时处理");
        }
        return Map.of("status", STATUS_PENDING_STREET_PROCESS);
    }

    // ============================================================ 11.1.1 工单接收确认

    /**
     * 工单接收确认（API 11.1.1）：待街道办接收 → 处理中；超24h未接收自动提醒、超48h自动预警由定时任务处理
     */
    @Transactional
    public Map<String, Object> receive(Long id, String acceptRemark, LoginUser user) {
        WorkOrder order = requireStreetOrder(id);
        requireNotArchived(order);
        requireStreetScope(order, user);
        if (!STATUS_PENDING_STREET_PROCESS.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可接收");
        }
        // 接收环节已并入「处理」，此处只留痕、不改变状态
        orderLogService.record(id, "RECEIVE", user.userId(), user.userType(),
                "街道办接收确认" + (acceptRemark == null || acceptRemark.isBlank() ? "" : "：" + acceptRemark),
                STATUS_PENDING_STREET_PROCESS, STATUS_PENDING_STREET_PROCESS);
        return Map.of("status", order.getStatus());
    }

    // ============================================================ 9.4 街道办提交处置结果

    /**
     * 提交处置结果（API 9.4）：处理中 → 待部门审核；提交后不可再修改（除非被部门审核退回）
     */
    @Transactional
    public Map<String, Object> dispose(Long id, DisposeRequest request, LoginUser user) {
        WorkOrder order = requireStreetOrder(id);
        requireNotArchived(order);
        requireStreetScope(order, user);
        if (!STATUS_PENDING_STREET_PROCESS.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可处理");
        }
        // 附件校验：附件须属于该工单（API 11.1.2 上传）
        if (request.attachmentIds() != null) {
            for (Long aid : request.attachmentIds()) {
                List<OrderDisposeAttachment> attachments = disposeAttachmentMapper.selectByOrderId(id);
                boolean found = attachments.stream().anyMatch(a -> a.getId().equals(aid));
                if (!found) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "附件 " + aid + " 不属于该工单");
                }
            }
        }
        order.setDisposePlan(request.disposePlan());
        order.setDisposeResult(request.disposeResult());
        order.setDisposeTag(request.disposeTag());
        order.setDisposeTime(parseIso(request.disposeTime()));
        order.setDisposeUserId(user.userId());
        order.setDisposeUserName(request.disposeUserName());
        order.setDeptAuditStatus("PENDING");
        // 街道办处置完成后进入「待市民评价」：市民可在7天窗口内评价，部门收评价或超期后才可审核
        order.setStatus(STATUS_PENDING_EVALUATE);
        orderCopySyncService.sync(order);
        orderLogService.record(id, "DISPOSE", user.userId(), user.userType(),
                "街道办处理完成（" + request.disposeTag() + "），待市民评价", STATUS_PENDING_STREET_PROCESS, STATUS_PENDING_EVALUATE);
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-dispose",
                "街道办处理工单：" + order.getOrderNo() + "（" + request.disposeTag() + "），待市民评价");
        return Map.of("status", STATUS_PENDING_EVALUATE);
    }

    // ============================================================ 街道办驳回（终结候选）

    /**
     * 街道办驳回：待街道办处理 → 街道办已驳回（回部门端处理），驳回原因必填
     */
    @Transactional
    public Map<String, Object> rejectOrder(Long id, String rejectReason, String rejectRemark, LoginUser user) {
        WorkOrder order = requireStreetOrder(id);
        requireNotArchived(order);
        requireStreetScope(order, user);
        if (!STATUS_PENDING_STREET_PROCESS.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可驳回");
        }
        String before = order.getStatus();
        order.setStatus(STATUS_STREET_REJECTED);
        orderCopySyncService.sync(order);
        orderLogService.record(id, "STREET_REJECT", user.userId(), user.userType(),
                "街道办驳回：" + rejectReason
                        + (rejectRemark == null || rejectRemark.isBlank() ? "" : "（" + rejectRemark + "）"),
                before, order.getStatus());
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-reject",
                "街道办驳回工单：" + order.getOrderNo() + "（" + rejectReason + "）");
        return Map.of("status", order.getStatus());
    }

    /**
     * 部门同意驳回：街道办已驳回 → 部门已驳回（回管理端终审），部门说明必填
     */
    @Transactional
    public Map<String, Object> deptReject(Long id, String rejectRemark, LoginUser user) {
        WorkOrder order = requireDeptOrder(id);
        requireNotArchived(order);
        requireDeptScope(order, user);
        if (!STATUS_STREET_REJECTED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可同意驳回");
        }
        String before = order.getStatus();
        order.setStatus(STATUS_DEPT_REJECTED);
        order.setDeptAuditRemark(rejectRemark);
        orderCopySyncService.sync(order);
        orderLogService.record(id, "DEPT_REJECT", user.userId(), user.userType(),
                "部门同意驳回" + (rejectRemark == null || rejectRemark.isBlank() ? "" : "：" + rejectRemark),
                before, order.getStatus());
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-reject",
                "部门同意驳回工单：" + order.getOrderNo());
        return Map.of("status", order.getStatus());
    }

    /**
     * 部门重新分派：街道办已驳回 → 待分派（部门端重新选街道办）
     */
    @Transactional
    public Map<String, Object> deptReassign(Long id, LoginUser user) {
        WorkOrder order = requireDeptOrder(id);
        requireNotArchived(order);
        requireDeptScope(order, user);
        if (!STATUS_STREET_REJECTED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可重新分派");
        }
        String before = order.getStatus();
        order.setStatus(STATUS_PENDING_DEPT_ASSIGN);
        orderCopySyncService.sync(order);
        orderLogService.record(id, "DEPT_REASSIGN", user.userId(), user.userType(),
                "部门对街道办驳回的工单重新分派", before, order.getStatus());
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-reassign",
                "部门重新分派驳回工单：" + order.getOrderNo());
        return Map.of("status", order.getStatus());
    }

    // ============================================================ 11.1.3 街道办退回申请

    /**
     * 街道办退回申请（API 11.1.3）：处理中 → 待部门分派，退回原因必填≤500字
     */
    @Transactional
    public Map<String, Object> applyReturn(Long id, String returnReason, LoginUser user) {
        WorkOrder order = requireStreetOrder(id);
        requireNotArchived(order);
        requireStreetScope(order, user);
        if (!STATUS_PENDING_STREET_PROCESS.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可申请退回");
        }
        order.setStreetReturnCount(order.getStreetReturnCount() == null ? 1 : order.getStreetReturnCount() + 1);
        order.setStatus(STATUS_PENDING_DEPT_ASSIGN);
        orderCopySyncService.sync(order);
        orderLogService.record(id, "RETURN", user.userId(), user.userType(),
                "街道办申请退回：" + returnReason, STATUS_PENDING_STREET_PROCESS, STATUS_PENDING_DEPT_ASSIGN);
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-return",
                "街道办退回：" + order.getOrderNo() + "（" + returnReason + "）");
        return Map.of("status", "PENDING_DEPT_ASSIGN");
    }

    // ============================================================ 11.1.4 街道办延期申请

    /**
     * 街道办延期申请（API 11.1.4）：延期最多2次、每次≤3个工作日，部门审批通过后更新 deadline
     */
    @Transactional
    public Map<String, Object> applyDelay(Long id, String delayReason, String expectedTime, LoginUser user) {
        WorkOrder order = requireStreetOrder(id);
        requireNotArchived(order);
        requireStreetScope(order, user);
        if (!STATUS_PENDING_STREET_PROCESS.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可申请延期");
        }
        int delayCount = order.getDelayCount() == null ? 0 : order.getDelayCount();
        if (delayCount >= MAX_DELAY_TIMES) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "延期申请已达上限（最多" + MAX_DELAY_TIMES + "次）");
        }
        LocalDateTime expected = parseIso(expectedTime);
        // 每次延期不超过3个工作日（需求 25(2)，代码硬编码校验）
        LocalDateTime maxExpected = addWorkdays(LocalDateTime.now(), MAX_DELAY_DAYS);
        if (expected.isAfter(maxExpected)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "延期时长不能超过" + MAX_DELAY_DAYS + "个工作日");
        }
        OrderDelayApply apply = new OrderDelayApply();
        apply.setOrderId(id);
        apply.setApplyUserId(user.userId());
        apply.setApplyUserName(user.jobNo());
        apply.setDelayReason(delayReason);
        apply.setExpectedTime(expected);
        apply.setStatus("PENDING");
        delayApplyMapper.insert(apply);
        orderLogService.record(id, "DELAY", user.userId(), user.userType(),
                "申请延期至 " + expectedTime + "：" + delayReason, order.getStatus(), order.getStatus());
        return Map.of("apply_id", apply.getId(), "status", "PENDING");
    }

    // ============================================================ 11.2.2 部门审核通过

    /**
     * 部门审核通过（API 11.2.2）：街道办已处理 → 已审核（DEPT_AUDITED；终审确认由管理端在工单管理执行）
     */
    @Transactional
    public Map<String, Object> auditApprove(Long id, LoginUser user) {
        WorkOrder order = requireDeptOrder(id);
        requireNotArchived(order);
        requireDeptScope(order, user);
        // 可入态：新流程「待市民评价」/ 市民已评价「EVALUATED」+ 存量「街道办已处理」（兼容历史 STREET_DONE 工单）
        if (!STATUS_PENDING_EVALUATE.equals(order.getStatus())
                && !"EVALUATED".equals(order.getStatus())
                && !STATUS_STREET_DONE.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可审核");
        }
        String before = order.getStatus();
        // 已评价（EVALUATED）视为满足条件；其余动态判定：市民已评价 或 处置完成满7天（无定时任务）
        boolean evaluated = "EVALUATED".equals(before) || evaluateMapper.selectByOrderId(id) != null;
        boolean windowExpired = order.getDisposeTime() != null
                && order.getDisposeTime().plusDays(EVALUATE_WINDOW_DAYS).isBefore(LocalDateTime.now());
        if (!evaluated && !windowExpired) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "待市民评价：收到市民评价或处置完成满 " + EVALUATE_WINDOW_DAYS + " 天后，方可审核");
        }
        order.setDeptAuditStatus("APPROVED");
        order.setDeptAuditTime(LocalDateTime.now());
        order.setDeptAuditUserId(user.userId());
        order.setDeptAuditUserName(user.jobNo());
        order.setStatus(STATUS_DEPT_AUDITED);
        order.setCloseTime(LocalDateTime.now());
        orderCopySyncService.sync(order);
        orderLogService.record(id, "AUDIT", user.userId(), user.userType(),
                "部门审核通过（" + (evaluated ? "已收到市民评价" : "评价窗口超期") + "），工单已审核",
                before, STATUS_DEPT_AUDITED);
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-audit",
                "部门审核通过：" + order.getOrderNo() + "（" + (evaluated ? "已评价" : "评价超期") + "）");
        // 市民办结通知（保持部门审核时通知，处置后7天内评价）
        AppealChild appeal = order.getSourceAppealId() == null ? null : findAppealById(order.getSourceAppealId());
        if (appeal != null) {
            pushMessage(null, appeal.getCitizenPhone(), "ORDER_CLOSED",
                    "您的诉求已办结",
                    "诉求 " + appeal.getAppealNo() + " 已办结，处置结果：" + summary(order.getDisposeResult()));
        }
        return Map.of("status", STATUS_DEPT_AUDITED);
    }

    /**
     * 终审确认（新流程）：已办结/已评价 → 确认归档（转入终审归档页）或退回（部门/街道办重新流转）
     * action=archive：终审归档（archive_type=FINAL）；action=reject：按 rejectTarget 退回（dept/street）
     */
    @Transactional
    public Map<String, Object> finalConfirm(Long id, String action, String rejectReason,
                                            String rejectTarget, LoginUser user) {
        WorkOrder order = requireOrder(id);
        requireNotArchived(order);
        if (!STATUS_DEPT_AUDITED.equals(order.getStatus()) && !"EVALUATED".equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅已审核或已评价工单可终审确认");
        }
        String before = order.getStatus();
        if ("reject".equals(action)) {
            if (rejectReason == null || rejectReason.isBlank()) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "退回原因不能为空");
            }
            order.setFinalAuditStatus("REJECTED");
            order.setFinalAuditRemark(rejectReason);
            order.setFinalRejectTarget(rejectTarget);
            if ("street".equals(rejectTarget)) {
                order.setStatus(STATUS_PENDING_STREET_PROCESS);
                // bug修复：终审直接退回街道办时刷新到达时间基准，街道办端未读红点重新亮起
                order.setStreetReceiveTime(LocalDateTime.now());
            } else {
                order.setStatus(STATUS_PENDING_DEPT_ASSIGN);
            }
            orderCopySyncService.sync(order);
            orderLogService.record(id, "FINAL_AUDIT", user.userId(), "ADMIN",
                    "终审退回（" + ("street".equals(rejectTarget) ? "直接退回街道办" : "退回部门")
                            + "）：" + rejectReason, before, order.getStatus());
            auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-final-audit",
                    "终审退回：" + order.getOrderNo() + "（" + rejectTarget + "）");
            return Map.of("status", order.getStatus());
        }
        // archive：终审确认归档，转入终审归档页
        order.setFinalAuditStatus("APPROVED");
        order.setFinalAuditTime(LocalDateTime.now());
        order.setFinalAuditUserId(user.userId());
        order.setFinalAuditUserName(user.jobNo());
        order.setIsArchived(1);
        order.setArchiveTime(LocalDateTime.now());
        order.setArchiveType("FINAL");
        // 完成标识：终审归档路径工单已办结（部门审核通过后终审确认）
        order.setIsCompleted(1);
        orderCopySyncService.sync(order);
        // 终审归档独立表：整行快照写入 admin_order_archive（先删同 id 旧快照，重开后再归档时覆盖）
        adminOrderArchiveMapper.deleteById(id);
        adminOrderArchiveMapper.copyFromAdmin(id);
        orderLogService.record(id, "FINAL_AUDIT", user.userId(), "ADMIN",
                "终审确认归档，工单转入终审归档页", before, order.getStatus());
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-final-audit",
                "终审确认归档：" + order.getOrderNo());
        return Map.of("status", order.getStatus(), "is_archived", true, "archive_type", "FINAL");
    }

    // ============================================================ 11.2.3 部门审核退回

    /**
     * 部门审核退回（API 11.2.3）：退回街道办重新处理；
     * 退回上限：被部门退回街道办最多2次，第3次强制升级至 admin 协调（需求 25(2)，代码硬编码校验）
     */
    @Transactional
    public Map<String, Object> auditReject(Long id, String rejectReason, LoginUser user) {
        WorkOrder order = requireDeptOrder(id);
        requireNotArchived(order);
        requireDeptScope(order, user);
        if (!STATUS_PENDING_EVALUATE.equals(order.getStatus())
                && !STATUS_STREET_DONE.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可退回");
        }
        String before = order.getStatus();
        int nextCount = (order.getDeptReturnCount() == null ? 0 : order.getDeptReturnCount()) + 1;
        if (nextCount > MAX_DEPT_RETURN) {
            // 第3次退回：强制升级 admin 协调（不再退回街道办）
            order.setStatus(STATUS_PENDING_DEPT_ASSIGN);
            order.setDeptAuditStatus("REJECTED");
            order.setDeptAuditRemark(rejectReason);
            orderCopySyncService.sync(order);
            orderLogService.record(id, "UPGRADE", user.userId(), user.userType(),
                    "部门退回次数超限（" + nextCount + "次），强制升级 admin 协调：" + rejectReason,
                    before, STATUS_PENDING_DEPT_ASSIGN);
            pushMessage(null, null, "ORDER_RETURNED", "工单强制升级",
                    "工单 " + order.getOrderNo() + " 部门退回次数超限，已强制升级 admin 协调");
            auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-audit",
                    "退回超限强制升级：" + order.getOrderNo());
            return Map.of("status", "PENDING_DEPT_ASSIGN");
        }
        order.setDeptReturnCount(nextCount);
        order.setDeptAuditStatus("REJECTED");
        order.setDeptAuditRemark(rejectReason);
        order.setStatus(STATUS_PENDING_STREET_PROCESS);
        // bug修复：退回重处理时刷新到达街道办时间基准，街道办端未读红点重新亮起
        order.setStreetReceiveTime(LocalDateTime.now());
        orderCopySyncService.sync(order);
        orderLogService.record(id, "RETURN", user.userId(), user.userType(),
                "部门审核退回（第" + nextCount + "次）：" + rejectReason, before, STATUS_PENDING_STREET_PROCESS);
        // 街道办收到退回通知（需求 22(2)）
        for (Map<String, Object> u : listStreetStaff(order.getStreetOffice())) {
            pushMessage(Long.valueOf(String.valueOf(u.get("id"))), null, "ORDER_RETURNED",
                    "处置结果被退回", "工单 " + order.getOrderNo() + " 处置结果被部门退回，请重新处理：" + rejectReason);
        }
        return Map.of("status", STATUS_PENDING_STREET_PROCESS);
    }

    // ============================================================ 11.2.4 批量审核

    /**
     * 批量审核（API 11.2.4）：批量通过限同类处置标签；敏感/紧急工单（一级）不支持批量，必须逐条审核
     */
    @Transactional
    public Map<String, Object> batchAudit(BatchAuditRequest request, LoginUser user) {
        int success = 0;
        int fail = 0;
        String myCat = user.userType(); // 批量审核仅限本人分类(user_type)工单
        String firstTag = null;
        for (Long id : request.orderIds()) {
            try {
                WorkOrder order = requireOrder(id);
                if ("一级".equals(order.getLevel())) {
                    fail++;
                    continue;
                }
                if (!myCat.equals(order.getCategory())) {
                    fail++;
                    continue;
                }
                if ("APPROVE".equals(request.action())) {
                    if (firstTag == null) {
                        firstTag = order.getDisposeTag();
                    } else if (!firstTag.equals(order.getDisposeTag())) {
                        fail++; // 限同类处置标签
                        continue;
                    }
                    auditApprove(id, user);
                } else {
                    if (request.rejectReason() == null || request.rejectReason().isBlank()) {
                        fail++;
                        continue;
                    }
                    auditReject(id, request.rejectReason(), user);
                }
                success++;
            } catch (Exception e) {
                fail++;
            }
        }
        return Map.of("success_count", success, "fail_count", fail);
    }

    // ============================================================ 11.2.5 处置质量评分

    /**
     * 处置质量评分（API 11.2.5）：1-5星，维度含时效性/效果/完整性，仅部门内部可见
     */
    @Transactional
    public Map<String, Object> score(Long id, ScoreRequest request, LoginUser user) {
        WorkOrder order = requireDeptOrder(id);
        requireDeptScope(order, user);
        if (!STATUS_PENDING_EVALUATE.equals(order.getStatus())
                && !STATUS_STREET_DONE.equals(order.getStatus())
                && !STATUS_DEPT_AUDITED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前状态不可评分");
        }
        order.setDeptScore(request.score());
        order.setDeptScoreTime(LocalDateTime.now());
        orderCopySyncService.sync(order);
        orderLogService.record(id, "SCORE", user.userId(), user.userType(),
                "部门评分：" + request.score() + "星", null, null);
        return Map.of("score", request.score());
    }

    // ============================================================ 11.2.6 延期审批

    /**
     * 部门延期审批（API 11.2.6）：通过后自动更新工单截止时间（deadline），结果写入 order_log
     */
    @Transactional
    public Map<String, Object> delayApprove(Long applyId, DelayApproveRequest request, LoginUser user) {
        OrderDelayApply apply = delayApplyMapper.selectById(applyId);
        if (apply == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "延期申请不存在");
        }
        if (!"PENDING".equals(apply.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该延期申请已审批");
        }
        WorkOrder order = requireDeptOrder(apply.getOrderId());
        requireDeptScope(order, user);
        if ("APPROVE".equals(request.action())) {
            apply.setStatus("APPROVED");
            order.setDeadline(apply.getExpectedTime());
            order.setDelayCount((order.getDelayCount() == null ? 0 : order.getDelayCount()) + 1);
            orderLogService.record(order.getId(), "DELAY", user.userId(), user.userType(),
                    "延期审批通过，截止时间更新为 " + apply.getExpectedTime(), null, null);
        } else {
            apply.setStatus("REJECTED");
            orderLogService.record(order.getId(), "DELAY", user.userId(), user.userType(),
                    "延期审批驳回" + (request.remark() == null ? "" : "：" + request.remark()), null, null);
        }
        apply.setApproveUserId(user.userId());
        apply.setApproveTime(LocalDateTime.now());
        apply.setApproveRemark(request.remark());
        delayApplyMapper.updateApprove(apply);
        orderCopySyncService.sync(order);
        return Map.of("status", apply.getStatus());
    }

    // ============================================================ 6.10 管理员手动分派/转派

    /**
     * 管理员手动分派/转派（API 6.10）：跨部门转派、调整承办街道办；指定街道办后进入待街道办接收
     */
    @Transactional
    public Map<String, Object> dispatch(Long id, OrderDispatchRequest request, LoginUser user) {
        WorkOrder order = requireOrder(id);
        requireNotArchived(order);
        // 被驳回回到管理端的工单（部门已驳回/待重新分派）支持「重新分类 + 重新分派」一步完成
        boolean reclassify = STATUS_DEPT_REJECTED.equals(order.getStatus())
                || STATUS_PENDING_REASSIGN.equals(order.getStatus());
        if (!STATUS_PENDING_DISPATCH.equals(order.getStatus())
                && !STATUS_PENDING_REASSIGN.equals(order.getStatus())
                && !reclassify) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅待分派/待重新分派工单可执行分派");
        }
        String beforeStatus = order.getStatus();
        String beforeDept = order.getDepartment();
        // 分派弹窗选择的区县：回填/修正工单区县（56部门口径：部门端按「分类+区县」隔离可见）
        if (request.countyCode() != null && !request.countyCode().isBlank()) {
            SysRegion county = regionService.getByCode(request.countyCode());
            if (county == null || !"COUNTY".equals(county.getLevel())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "请选择正确的所属区县");
            }
            order.setCountyCode(county.getRegionCode());
            order.setCountyName(county.getRegionName());
        }
        // 重新分类：携带新的一级/二级分类与承办街道办时一并更新
        if (request.category() != null && !request.category().isBlank()) {
            order.setCategory(request.category().trim());
            order.setSubCategory(request.subCategory() == null || request.subCategory().isBlank()
                    ? null : request.subCategory().trim());
            // 分类变更 → 分派部门跟随一级分类（需求 12(1)）；OTHER（其他）无映射时用请求体 department
            String mapped = deptDisplay(order, order.getCategory());
            order.setDepartment(mapped != null ? mapped
                    : (request.department() == null || request.department().isBlank() ? null : request.department().trim()));
        } else if ((order.getDepartment() == null || order.getDepartment().isBlank())
                && request.department() != null && !request.department().isBlank()) {
            // OTHER（其他）类工单无默认部门：管理端分派弹窗手动指定部门
            order.setDepartment(request.department().trim());
        }
        if (order.getDepartment() == null || order.getDepartment().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "工单尚未确定分派部门，请先在详情中编辑类别");
        }
        if (request.streetOffice() != null && !request.streetOffice().isBlank()) {
            order.setStreetOffice(request.streetOffice().trim());
        }
        if (reclassify) {
            // 重新分类分派：清除上次的部门审核驳回意见，重新走审核流程
            order.setDeptAuditRemark(null);
        }
        order.setStatus(STATUS_PENDING_DEPT_PROCESS);
        // 记录工单到达本部门时间（部门端未读红点基准）
        order.setDeptReceiveTime(LocalDateTime.now());
        orderCopySyncService.sync(order);
        orderLogService.record(id, "DISPATCH", user.userId(), user.userType(),
                (reclassify ? "重新分类分派至部门：" : "管理端分派至部门：") + order.getDepartment()
                        + (request.remark() == null ? "" : "（" + request.remark() + "）"),
                // beforeStatus/beforeDept 可能为 null（部门申请驳回清空 department）：Map.of 禁止 null 值，需判空
                Map.of("status", beforeStatus == null ? "" : beforeStatus,
                        "department", beforeDept == null ? "" : beforeDept),
                Map.of("status", order.getStatus(),
                        "department", order.getDepartment() == null ? "" : order.getDepartment()));
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(),
                reclassify ? "order-reassign" : "order-dispatch",
                (reclassify ? "重新分派工单：" : "派发工单：") + order.getOrderNo()
                        + " → " + order.getDepartment()
                        + (order.getStreetOffice() == null ? "" : " → " + order.getStreetOffice()));
        // 部门端收到工单日志（操作人=英文部门代号 URBAN 等，操作类型中文由前端 AuditLogPanel 映射显示）
        auditLogService.recordOperation(order.getCategory(), "order-receive",
                "收到工单：" + order.getOrderNo() + "（来自管理端 " + user.jobNo() + "，派发至 " + order.getDepartment() + "）",
                deptReceiveTarget(order));
        return Map.of("status", order.getStatus());
    }

    // ============================================================ 11.3.1 待终审工单列表

    public PageData<Map<String, Object>> finalAuditingPage(String department, String streetOffice,
                                                           String disposeTag, Boolean isOverdue,
                                                           int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        // 终审归档独立表：终审归档列表读 admin_order_archive
        List<Map<String, Object>> rows = adminOrderArchiveMapper.selectFinalAuditing(
                department, streetOffice, disposeTag, isOverdue, offset, pageSize);
        long total = adminOrderArchiveMapper.countFinalAuditing(department, streetOffice, disposeTag, isOverdue);
        return PageData.of(rows, total, page, pageSize);
    }

    // ============================================================ 11.3.2 全链路信息查看

    /**
     * 全链路信息查看（API 11.3.2）：原始诉求→AI分类→分派→处置→部门审核→时间线
     */
    public Map<String, Object> fullChain(Long id) {
        WorkOrder order = requireOrder(id);
        Map<String, Object> data = new LinkedHashMap<>();
        AppealChild appeal = order.getSourceAppealId() == null ? null : findAppealById(order.getSourceAppealId());
        if (appeal != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("appeal_no", appeal.getAppealNo());
            a.put("title", appeal.getTitle());
            a.put("content", appeal.getContent());
            a.put("citizen_name", MaskUtil.maskName(appeal.getCitizenName()));
            a.put("citizen_phone", MaskUtil.maskPhone(appeal.getCitizenPhone()));
            data.put("appeal", a);
        }
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("category", order.getCategory());
        ai.put("sub_category", order.getSubCategory());
        ai.put("confidence", order.getConfidence());
        ai.put("hit_keywords", order.getHitKeywords() == null ? List.of()
                : List.of(order.getHitKeywords().split(",")));
        data.put("ai_classify", ai);
        Map<String, Object> dispatch = new LinkedHashMap<>();
        dispatch.put("department", order.getDepartment());
        dispatch.put("street_office", order.getStreetOffice());
        data.put("dispatch", dispatch);
        Map<String, Object> dispose = new LinkedHashMap<>();
        dispose.put("plan", order.getDisposePlan());
        dispose.put("result", order.getDisposeResult());
        dispose.put("tag", order.getDisposeTag());
        dispose.put("time", order.getDisposeTime());
        data.put("dispose", dispose);
        Map<String, Object> deptAudit = new LinkedHashMap<>();
        deptAudit.put("status", order.getDeptAuditStatus());
        deptAudit.put("remark", order.getDeptAuditRemark());
        deptAudit.put("score", order.getDeptScore());
        data.put("dept_audit", deptAudit);
        Map<String, Object> finalAudit = new LinkedHashMap<>();
        finalAudit.put("status", order.getFinalAuditStatus());
        finalAudit.put("remark", order.getFinalAuditRemark());
        data.put("final_audit", finalAudit);
        data.put("timeline", orderLogService.timeline(id));
        return data;
    }

    // ============================================================ 工单详情（含合并子诉求）

    /**
     * 工单详情（工单分页「查看」弹窗用）：工单基本信息 + 所有合并子诉求的内容/地点/提交时间/市民信息
     */
    public Map<String, Object> getOrderDetail(Long id) {
        WorkOrder order = requireOrder(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", order.getId());
        data.put("order_no", order.getOrderNo());
        data.put("title", order.getTitle());
        data.put("content", order.getContent());
        data.put("category", order.getCategory());
        data.put("sub_category", order.getSubCategory());
        data.put("department", order.getDepartment());
        data.put("street_office", order.getStreetOffice());
        // 详情弹窗「部门/街道办」下拉数据源：区县编码（加载下属街道列表）与辖区回显
        data.put("county_code", order.getCountyCode());
        data.put("county_name", order.getCountyName());
        data.put("region", order.getRegion());
        data.put("is_archived", order.getIsArchived());
        // 归档快照信息（终审归档页查看弹窗用）：归档时间/类型/完成标识/办结时间/终审字段
        data.put("archive_time", order.getArchiveTime());
        data.put("archive_type", order.getArchiveType());
        data.put("is_completed", order.getIsCompleted());
        data.put("close_time", order.getCloseTime());
        data.put("final_audit_status", order.getFinalAuditStatus());
        data.put("final_audit_time", order.getFinalAuditTime());
        data.put("final_audit_user_name", order.getFinalAuditUserName());
        data.put("final_audit_remark", order.getFinalAuditRemark());
        data.put("status", order.getStatus());
        data.put("level", order.getLevel());
        data.put("attention_count", order.getAttentionCount());
        data.put("create_time", order.getCreateTime());
        // 归档详情弹窗全字段：终审归档页只读弹窗依赖以下字段，缺一即显示为空
        data.put("event_type", order.getEventType());
        data.put("confidence", order.getConfidence());
        data.put("hit_keywords", order.getHitKeywords());
        data.put("need_review", order.getNeedReview());
        data.put("dispose_tag", order.getDisposeTag());
        data.put("dispose_plan", order.getDisposePlan());
        data.put("dispose_result", order.getDisposeResult());
        data.put("dispose_user_name", order.getDisposeUserName());
        data.put("dispose_time", order.getDisposeTime());
        data.put("is_overdue", order.getIsOverdue());
        data.put("delay_count", order.getDelayCount());
        data.put("reopen_count", order.getReopenCount());
        data.put("dept_audit_status", order.getDeptAuditStatus());
        data.put("dept_audit_time", order.getDeptAuditTime());
        data.put("dept_audit_remark", order.getDeptAuditRemark());
        // 所有合并子诉求
        List<Map<String, Object>> appeals = new ArrayList<>();
        List<Long> appealIds = workOrderAppealMapper.selectAppealIdsByOrderId(id);
        if (appealIds != null && !appealIds.isEmpty()) {
            for (AppealChild c : appealChildMapper.selectByIds(appealIds)) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("appeal_no", c.getAppealNo());
                a.put("content", c.getContent());
                a.put("location", childLocation(c));
                a.put("citizen_name", MaskUtil.maskName(c.getCitizenName()));
                a.put("citizen_phone", MaskUtil.maskPhone(c.getCitizenPhone()));
                a.put("submit_time", c.getCreateTime());
                appeals.add(a);
            }
        }
        data.put("appeals", appeals);
        // 诉求附件（来源：组成子诉求所属父诉求的市民上传附件；MinIO 预签名下载链接）
        // 空集合保护：无关联子诉求的工单（如测试/手工工单）直接跳过，避免空 IN 语法错误
        java.util.List<Map<String, Object>> appealFiles = new ArrayList<>();
        if (appealIds != null && !appealIds.isEmpty()) {
        java.util.Set<Long> parentIds = new java.util.HashSet<>();
        for (AppealChild c : appealChildMapper.selectByIds(appealIds)) {
            if (c.getParentId() == null || !parentIds.add(c.getParentId())) continue;
            for (org.tjetc.backend.entity.common.SysFile f : sysFileMapper.selectByAppealId(c.getParentId())) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("file_id", f.getId());
                fm.put("file_name", f.getFileName());
                fm.put("url", fileService.presignedUrl(f.getFileUrl()));
                appealFiles.add(fm);
            }
        }
        }
        data.put("appeal_attachments", appealFiles);
        return data;
    }

    /** 子诉求地点拼接：区县 + 街道 + 社区 + 详细地址（宽泛诉求为全市） */
    private String childLocation(AppealChild child) {
        if (child.getBroadFlag() != null && child.getBroadFlag() == 1) {
            return "秦皇岛全市";
        }
        List<String> parts = new ArrayList<>();
        if (child.getDistrict() != null && !child.getDistrict().isBlank()) parts.add(child.getDistrict());
        if (child.getStreet() != null && !child.getStreet().isBlank()) parts.add(child.getStreet());
        if (child.getCommunity() != null && !child.getCommunity().isBlank()) parts.add(child.getCommunity());
        if (child.getAddressDetail() != null && !child.getAddressDetail().isBlank()) parts.add(child.getAddressDetail());
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    // ============================================================ 11.3.3 终审通过（新流程由 finalConfirm 承担）

    /**
     * 【已废弃→保留方法签名防编译依赖】旧终审通过：新流程请用 finalConfirm。
     * 兼容 CLOSED 调用（等同 finalConfirm 的归档分支）。
     */
    public Map<String, Object> finalApprove(Long id, LoginUser user) {
        return finalConfirm(id, "archive", null, null, user);
    }

    // ============================================================ 11.3.4 终审退回

    /**
     * 终审退回（已委托给 finalConfirm 的 reject 分支）：保留签名防编译依赖
     */
    public Map<String, Object> finalReject(Long id, FinalRejectRequest request, LoginUser user) {
        return finalConfirm(id, "reject", request.rejectReason(), request.rejectTarget(), user);
    }

    // ============================================================ 11.3.5 批量终审

    /**
     * 批量终审（API 11.3.5）：批量通过限同一部门；敏感/紧急（一级）工单不支持批量，必须逐条审核
     */
    public Map<String, Object> batchFinalAudit(BatchFinalRequest request, LoginUser user) {
        int success = 0;
        int fail = 0;
        String firstDept = null;
        for (Long id : request.orderIds()) {
            try {
                WorkOrder order = requireOrder(id);
                if ("一级".equals(order.getLevel())) {
                    fail++;
                    continue;
                }
                if ("APPROVE".equals(request.action())) {
                    if (firstDept == null) {
                        firstDept = order.getDepartment();
                    } else if (!firstDept.equals(order.getDepartment())) {
                        fail++; // 限同一部门
                        continue;
                    }
                    finalApprove(id, user);
                } else {
                    if (request.rejectTarget() == null || request.rejectReason() == null) {
                        fail++;
                        continue;
                    }
                    finalReject(id, new FinalRejectRequest(request.rejectTarget(), request.rejectReason()), user);
                }
                success++;
            } catch (Exception e) {
                fail++;
            }
        }
        return Map.of("success_count", success, "fail_count", fail);
    }

    /** 工单流转时间线（API 11.1.5） */
    public List<Map<String, Object>> timelineOf(Long id) {
        return orderLogService.timeline(id);
    }

    // ============================================================ 11.3.6 手动归档

    /**
     * 手动归档（API 11.3.6）：已办结工单可手动归档；归档后只读不可修改
     */
    @Transactional
    public Map<String, Object> archive(Long id, LoginUser user) {
        WorkOrder order = requireOrder(id);
        if (!"CLOSED".equals(order.getStatus()) && !"EVALUATED".equals(order.getStatus())
                && !STATUS_DEPT_REJECTED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅已办结或已驳回工单可归档");
        }
        if (order.getIsArchived() != null && order.getIsArchived() == 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "工单已归档");
        }
        // 区分归档类型：成功处理归档 vs 驳回归档（每个工单单独记录）
        boolean rejected = STATUS_DEPT_REJECTED.equals(order.getStatus());
        String archiveType = rejected ? "REJECT" : "MANUAL";
        String archiveDesc = rejected ? "驳回归档（街道办/部门驳回，工单终结）" : "成功处理归档（办结）";
        order.setIsArchived(1);
        order.setArchiveTime(LocalDateTime.now());
        order.setArchiveType(archiveType);
        // 完成标识：已办结(CLOSED/EVALUATED)=1；驳回归档=0（未完成即终结）
        order.setIsCompleted(rejected ? 0 : 1);
        orderCopySyncService.sync(order);
        // 终审归档独立表：整行快照写入 admin_order_archive
        adminOrderArchiveMapper.deleteById(id);
        adminOrderArchiveMapper.copyFromAdmin(id);
        orderLogService.record(id, "ARCHIVE", user.userId(), "ADMIN", archiveDesc, null, null);
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-final-audit",
                archiveDesc + "：" + order.getOrderNo());
        return Map.of("is_archived", true, "archive_type", archiveType);
    }

    /**
     * 管理端终审重新分派：部门已驳回 → 待管理端分派（从头走流程）
     */
    @Transactional
    public Map<String, Object> reassign(Long id, LoginUser user) {
        WorkOrder order = requireOrder(id);
        requireNotArchived(order);
        if (!STATUS_DEPT_REJECTED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅部门已驳回工单可重新分派");
        }
        String before = order.getStatus();
        order.setStatus(STATUS_PENDING_DISPATCH);
        order.setDepartment(null);
        order.setStreetOffice(null);
        orderCopySyncService.sync(order);
        orderLogService.record(id, "REASSIGN", user.userId(), "ADMIN",
                "管理端终审重新分派", before, order.getStatus());
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-reassign",
                "重新分派工单：" + order.getOrderNo());
        return Map.of("status", order.getStatus());
    }

    /**
     * 重新分类（驳回工单）：body 为空时仅留痕（打开弹窗）；携带 category/department 时保存新分类并把工单退回「待分派」，
     * 真正分派由管理端随后点「分派」完成（重新分类不直接分派）
     */
    @Transactional
    public Map<String, Object> markReclassify(Long id, java.util.Map<String, String> body, LoginUser user) {
        WorkOrder order = requireOrder(id);
        requireNotArchived(order);
        // 部门驳回后回到管理端的两种状态均可重新分类：部门已驳回（DEPT_REJECTED）/ 待重新分派（PENDING_REASSIGN）
        if (!STATUS_DEPT_REJECTED.equals(order.getStatus())
                && !STATUS_PENDING_REASSIGN.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅被驳回回到管理端的工单可重新分类");
        }
        String categoryName = null;
        if (order.getCategory() != null && !order.getCategory().isBlank()) {
            ClassifyPrimary p = classifyPrimaryMapper.selectByCode(order.getCategory());
            categoryName = p == null ? order.getCategory() : p.getName();
        }
        // 留痕分支：打开弹窗时仅记录「重新分类」动作
        if (body == null || body.isEmpty()) {
            String summary = "重新分类工单：" + order.getOrderNo()
                    + "（原分类：" + (categoryName == null ? "-" : categoryName)
                    + (order.getSubCategory() == null ? "" : "/" + order.getSubCategory()) + "）";
            orderLogService.record(id, "RECLASSIFY", user.userId(), user.userType(),
                    summary, order.getStatus(), order.getStatus());
            auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-reclassify", summary);
            return Map.of("status", order.getStatus());
        }
        // 提交分支：保存新分类（类别/二级/部门），状态退回「待分派」，由「分派」按钮完成重新分派
        String category = trimToNull(body.get("category"));
        String subCategory = trimToNull(body.get("sub_category"));
        if (category == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "类别不能为空");
        }
        // 可选：重新分类弹窗选择的区县（未选则沿用工单原区县）
        String countyCode = trimToNull(body.get("county_code"));
        if (countyCode != null) {
            SysRegion county = regionService.getByCode(countyCode);
            if (county == null || !"COUNTY".equals(county.getLevel())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "请选择正确的所属区县");
            }
            order.setCountyCode(county.getRegionCode());
            order.setCountyName(county.getRegionName());
        }
        // 分派部门强制跟随一级分类（与分派只读一致），不接受请求体手写部门；展示名=区县+部门拼接
        String department = deptDisplay(order, category);
        if (department == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该分类未配置分派部门");
        }
        String before = order.getStatus();
        order.setCategory(category);
        order.setSubCategory(subCategory);
        order.setDepartment(department);
        // 重新分类只改分类与部门，不在此改承办街道办（街道办由部门分派时决定）
        order.setDeptAuditRemark(null);
        order.setStatus(STATUS_PENDING_DISPATCH);
        orderCopySyncService.sync(order);
        String newCategoryName = category;
        ClassifyPrimary p2 = classifyPrimaryMapper.selectByCode(category);
        if (p2 != null) {
            newCategoryName = p2.getName();
        }
        String summary2 = "重新分类工单：" + order.getOrderNo() + "（新分类：" + newCategoryName
                + (subCategory == null ? "" : "/" + subCategory) + " → " + department
                + "），已回到待分派";
        orderLogService.record(id, "RECLASSIFY", user.userId(), user.userType(),
                summary2, before, order.getStatus());
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-reassign", summary2);
        return Map.of("status", order.getStatus());
    }

    // ============================================================ 11.3.7 归档数据管理

    public PageData<Map<String, Object>> archivesPage(String year, String department, String category,
                                                      int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        // 终审归档独立表：归档数据列表读 admin_order_archive
        List<Map<String, Object>> rows =
                adminOrderArchiveMapper.selectArchives(year, department, category, offset, pageSize);
        long total = adminOrderArchiveMapper.countArchives(year, department, category);
        return PageData.of(rows, total, page, pageSize);
    }

    /** 归档数据导出（API 11.3.7 export=true）：构建 Excel 工作簿（由 Controller 流式回写，零落盘） */
    public XSSFWorkbook exportArchives(String year, String department, String category) {
        List<Map<String, Object>> rows = adminOrderArchiveMapper.selectArchives(year, department, category, 0, 100000);
        XSSFWorkbook workbook = ExcelUtil.createWorkbook();
        Sheet sheet = workbook.createSheet("归档工单");
        ExcelUtil.writeHeader(sheet, new String[]{"工单编号", "诉求摘要", "所属部门", "承办街道办", "分类", "等级", "归档时间", "归档类型", "办结时间"});
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            ExcelUtil.writeRow(sheet, i + 1, new Object[]{
                    v(r, "orderNo", "order_no"), v(r, "summary", "summary"),
                    v(r, "department", "department"), v(r, "streetOffice", "street_office"),
                    v(r, "category", "category"), v(r, "level", "level"),
                    v(r, "archiveTime", "archive_time"), v(r, "archiveType", "archive_type"),
                    v(r, "closeTime", "close_time")});
        }
        ExcelUtil.autoSize(sheet, 9);
        return workbook;
    }

    // ============================================================ 11.3.8 重开申请审批

    public PageData<Map<String, Object>> reopenApplicationsPage(int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> rows = adminWorkOrderMapper.selectReopenApplications(offset, pageSize);
        long total = adminWorkOrderMapper.countReopenApplications();
        return PageData.of(rows, total, page, pageSize);
    }

    /**
     * 重开申请审批（API 11.3.8）：通过后工单重新进入分派流程（待确认），保留原工单编号；
     * 每个工单最多重开1次（需求 25(2)，代码硬编码校验）
     */
    @Transactional
    public Map<String, Object> reopenApprove(Long id, ReopenApproveRequest request, LoginUser user) {
        WorkOrder order = requireOrder(id);
        OrderEvaluate evaluate = evaluateMapper.selectByOrderId(id);
        if (evaluate == null || evaluate.getIsReopenApplied() == null || evaluate.getIsReopenApplied() != 1
                || !"PENDING".equals(evaluate.getReopenStatus())) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "重开申请不存在或已审批");
        }
        if ("APPROVE".equals(request.action())) {
            int reopenCount = order.getReopenCount() == null ? 0 : order.getReopenCount();
            if (reopenCount >= MAX_REOPEN_TIMES) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "工单重开次数已达上限（最多1次）");
            }
            order.setReopenCount(reopenCount + 1);
            // 重开后保留原工单编号，重新进入分派流程；
            // bug修复：清空上一轮的分派路由与审核/归档残留字段，否则管理端分派列表残留旧部门且破坏副本不变式
            order.setStatus(STATUS_PENDING_DISPATCH);
            order.setDepartment(null);
            order.setStreetOffice(null);
            order.setDeptReceiveTime(null);
            order.setStreetReceiveTime(null);
            order.setDeptAuditStatus(null);
            order.setDeptAuditRemark(null);
            order.setFinalAuditStatus(null);
            order.setFinalAuditRemark(null);
            order.setFinalRejectTarget(null);
            order.setIsArchived(0);
            order.setArchiveTime(null);
            order.setArchiveType(null);
            order.setCloseTime(null);
            orderCopySyncService.sync(order);
            // 终审归档独立表：重开通过后删除归档快照（工单回到分派流程，归档页不再展示）
            adminOrderArchiveMapper.deleteById(id);
            evaluate.setReopenStatus("APPROVED");
            evaluate.setReopenApproveTime(LocalDateTime.now());
            evaluate.setReopenApproveRemark(request.remark());
            evaluateMapper.updateReopenApprove(evaluate);
            orderLogService.record(id, "REOPEN", user.userId(), "ADMIN",
                    "重开申请审批通过，工单重新进入分派流程", "CLOSED/EVALUATED", STATUS_PENDING_DISPATCH);
            pushMessage(null, evaluate.getCitizenPhone(), "ORDER_REOPENED",
                    "重开申请已通过",
                    "工单 " + order.getOrderNo() + " 重开申请已通过，重新进入处理流程，请关注后续进展");
            auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-reopen", "重开审批通过：" + order.getOrderNo());
        } else {
            evaluate.setReopenStatus("REJECTED");
            evaluate.setReopenApproveTime(LocalDateTime.now());
            evaluate.setReopenApproveRemark(request.remark());
            evaluateMapper.updateReopenApprove(evaluate);
            pushMessage(null, evaluate.getCitizenPhone(), "ORDER_REOPENED",
                    "重开申请未通过",
                    "工单 " + order.getOrderNo() + " 重开申请未通过" + (request.remark() == null ? "" : "：" + request.remark()));
        }
        return Map.of("reopen_status", evaluate.getReopenStatus(), "order_status", order.getStatus());
    }

    // ============================================================ 11.2.7 部门汇总上报

    /**
     * 部门汇总上报（API 11.2.7）：处置总量/通过率/退回率/平均处置时长/各街道办排名/超时数量
     */
    public Map<String, Object> orderReport(String period, LoginUser user) {
        String dept = user.userType(); // 按本人分类(user_type)统计本部门汇总
        LocalDateTime start = periodStart(period);
        // 三端数据解耦：部门汇总读部门端副本表
        List<Map<String, Object>> rows = deptWorkOrderMapper.selectDeptReport(dept, user.regionCode(), start);
        long total = rows.stream().mapToLong(r -> toLong(v(r, "cnt", "cnt"))).sum();
        long approved = rows.stream().mapToLong(r -> toLong(v(r, "approved", "approved"))).sum();
        long overdue = rows.stream().mapToLong(r -> toLong(v(r, "overdue", "overdue"))).sum();
        double avgHours = rows.stream().mapToDouble(r -> toDouble(v(r, "avgHours", "avg_hours"))).average().orElse(0);
        List<Map<String, Object>> ranking = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("street", v(r, "streetOffice", "street_office"));
            m.put("count", v(r, "cnt", "cnt"));
            m.put("avg_score", v(r, "avgScore", "avg_score"));
            ranking.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("approve_rate", total == 0 ? 0 : round2((double) approved / total));
        data.put("reject_rate", total == 0 ? 0 : round2((double) (total - approved) / total));
        data.put("avg_dispose_hours", round2(avgHours));
        data.put("street_ranking", ranking);
        data.put("overdue_count", overdue);
        return data;
    }

    private LocalDateTime periodStart(String period) {
        return switch (period == null ? "DAY" : period) {
            case "WEEK" -> LocalDateTime.now().minusDays(7);
            case "MONTH" -> LocalDateTime.now().minusDays(30);
            default -> LocalDateTime.now().minusDays(1);
        };
    }

    // ============================================================ 内部工具

    private AppealChild findAppealById(Long appealId) {
        return appealChildMapper.selectById(appealId);
    }

    /** 按工单辖区返回街道办选项（分派弹窗下拉用）：区县 → sys_region 下属街道/镇/乡全部列出，与是否注册账号无关 */
    public List<Map<String, Object>> streetOptionsFor(Long id, LoginUser user) {
        WorkOrder order = requireDeptOrder(id);
        requireDeptScope(order, user);
        String county = normalizeCounty(order.getCountyName() != null ? order.getCountyName() : order.getRegion());
        if (county == null) {
            return List.of();
        }
        String countyCode = regionService.resolveCountyCode(county);
        if (countyCode == null) {
            return List.of();
        }
        return regionService.listChildren(countyCode).stream()
                .map(r -> Map.<String, Object>of(
                        "label", r.getRegionName(),
                        "value", r.getRegionName()))
                .toList();
    }

    /**
     * 部门端详情弹窗修改承办街道办（只改街道办公文阵地，不改变流转状态）：
     * 部门在权限范围内的未归档工单均可修正街道办，切换后同步三端副本并留痕
     */
    @Transactional
    public Map<String, Object> updateDeptStreetOffice(Long id, String streetOffice, LoginUser user) {
        WorkOrder order = requireDeptOrder(id);
        requireNotArchived(order);
        requireDeptScope(order, user);
        if (streetOffice == null || streetOffice.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择承办街道办");
        }
        String before = order.getStatus();
        String oldStreet = order.getStreetOffice();
        order.setStreetOffice(streetOffice.trim());
        order.setStreetReceiveTime(LocalDateTime.now());
        orderCopySyncService.sync(order);
        orderLogService.record(id, "STREET_CHANGE", user.userId(), user.userType(),
                "部门端修改承办街道办：" + (oldStreet == null ? "未分派" : oldStreet) + " → " + streetOffice.trim(),
                before, before);
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-street-change",
                "部门端修改工单承办街道办：" + order.getOrderNo() + " → " + streetOffice.trim());
        return Map.of("street_office", order.getStreetOffice());
    }

    /**
     * 按工单区县固定办街道：仅在地址/区县信息中携带具体街道、镇或乡名时匹配回该街道办（与 street_office 存储口径一致，纯名无后缀）；
     * 入参可为区县名（海港区）、街道名（文化路街道）或完整地址（海港区 文化路街道 XXX），内部统一提取区县前缀，
     * 经 sys_region 反查该区县下属街道/镇/乡后按名称精确匹配；地址中不含街道名时返回 null（由用户手动选择）。
     */
    private String matchStreetOffice(String region) {
        if (region == null || region.isBlank()) {
            return null;
        }
        final String county = this.normalizeCounty(region);
        if (county == null) {
            return null;
        }
        String countyCode = regionService.resolveCountyCode(county);
        if (countyCode == null) {
            return null;
        }
        // 仅匹配地址中显式携带的街道/镇/乡名，避免无依据地自动塞入该区县第一个街道
        return regionService.listChildren(countyCode).stream()
                .map(SysRegion::getRegionName)
                .filter(region::contains)
                .findFirst()
                .orElse(null);
    }

    /** 从区县名/街道名/完整地址中提取区县名（七大区县前缀），无法识别返回 null */
    private String normalizeCounty(String region) {
        if (region == null) {
            return null;
        }
        for (String county : new String[]{"青龙满族自治县", "海港区", "山海关区", "北戴河区", "抚宁区", "昌黎县", "卢龙县"}) {
            if (region.equals(county) || region.startsWith(county)) {
                return county;
            }
        }
        return null;
    }

    /** 承办街道办的在职工作人员（入参可为带后缀「海港区街道办」或区县名；用于分派/退回站内通知） */
    private List<Map<String, Object>> listStreetStaff(String streetOffice) {
        if (streetOffice == null) {
            return List.of();
        }
        String region = streetOffice.endsWith("街道办")
                ? streetOffice.substring(0, streetOffice.length() - 3) : streetOffice;
        return sysUserMapper.selectEnabledByUserType("STREET").stream()
                .filter(u -> region.equals(u.getRegion()))
                .map(u -> Map.<String, Object>of("id", u.getId()))
                .toList();
    }

    private String summary(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 100 ? text.substring(0, 100) + "……" : text;
    }

    private long toLong(Object o) {
        if (o == null) {
            return 0;
        }
        return Long.parseLong(String.valueOf(o));
    }

    private double toDouble(Object o) {
        if (o == null) {
            return 0;
        }
        return Double.parseDouble(String.valueOf(o));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private Object v(Map<String, Object> m, String camel, String snake) {
        if (m.containsKey(camel)) {
            return m.get(camel);
        }
        return m.get(snake);
    }

    // ============================================================ 6.2 管理端工单查询

    /**
     * 管理端工单多条件分页查询（API 6.2）：archived null=全部 / 0=未归档(工单管理) / 1=已归档(终审归档页)
     */
    public PageData<Map<String, Object>> listOrders(String orderNo, String category, String level,
                                                    String region, String status, String keyword, String startDate,
                                                    String timeStart, String timeEnd,
                                                    Integer archived, int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        // 终审归档独立表：archived=1（终审归档页）读 admin_order_archive；其余读管理端权威表
        boolean archivedOnly = archived != null && archived == 1;
        List<Map<String, Object>> rows = archivedOnly
                ? adminOrderArchiveMapper.selectPage(orderNo, category, level, region, status, keyword, startDate, timeStart, timeEnd, offset, pageSize)
                : adminWorkOrderMapper.selectPage(orderNo, category, level, region, status, keyword, startDate, archived, offset, pageSize);
        long total = archivedOnly
                ? adminOrderArchiveMapper.countPage(orderNo, category, level, region, status, keyword, startDate, timeStart, timeEnd)
                : adminWorkOrderMapper.countPage(orderNo, category, level, region, status, keyword, startDate, archived);
        List<Map<String, Object>> list = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("order_no", v(r, "orderNo", "order_no"));
            m.put("title", r.get("title"));
            m.put("content", r.get("content"));
            m.put("category", r.get("category"));
            m.put("sub_category", v(r, "subCategory", "sub_category"));
            m.put("level", r.get("level"));
            m.put("attention_count", v(r, "attentionCount", "attention_count"));
            m.put("event_type", v(r, "eventType", "event_type"));
            m.put("confidence", r.get("confidence"));
            m.put("hit_keywords", v(r, "hitKeywords", "hit_keywords"));
            m.put("need_review", v(r, "needReview", "need_review"));
            m.put("region", r.get("region"));
            m.put("department", r.get("department"));
            m.put("street_office", v(r, "streetOffice", "street_office"));
            m.put("status", r.get("status"));
            m.put("is_overdue", v(r, "isOverdue", "is_overdue"));
            m.put("dispose_tag", v(r, "disposeTag", "dispose_tag"));
            m.put("create_time", v(r, "createTime", "create_time"));
            // 终审归档页「工单完成时间」列：部门审核通过时间；未完成工单（close_time 为空）前端按归档时间展示
            m.put("close_time", v(r, "closeTime", "close_time"));
            // 终审归档页专属列：归档时间 + 完成标识（快照表已返回；非归档分支为 null，前端不展示）
            if (archivedOnly) {
                m.put("archive_time", v(r, "archiveTime", "archive_time"));
                m.put("is_completed", v(r, "isCompleted", "is_completed"));
            }
            return m;
        }).toList();
        return PageData.of(list, total, page, pageSize);
    }

    // ============================================================ 6.3 待确认工单审核确认

    /**
     * 部门端确认（API 6.3）：PENDING_DEPT_PROCESS → PENDING_DEPT_ASSIGN（部门端待审核 → 待分派）
     */
    @Transactional
    public Map<String, Object> confirmOrder(Long id, LoginUser user) {
        WorkOrder order = requireDeptOrder(id);
        requireNotArchived(order);
        requireDeptScope(order, user);
        if (!STATUS_PENDING_DEPT_PROCESS.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅待部门端处理工单可执行确认");
        }
        String before = order.getStatus();
        order.setStatus(STATUS_PENDING_DEPT_ASSIGN);
        orderCopySyncService.sync(order);
        orderLogService.record(id, "DEPT_CONFIRM", user.userId(), user.userType(),
                "部门端确认，工单进入待分派", before, order.getStatus());
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-confirm",
                "部门确认工单：" + order.getOrderNo());
        return Map.of("order_no", order.getOrderNo(), "status", order.getStatus());
    }

    /**
     * 取消确认：待分派回退待确认（工单尚未正式分派前可撤回）
     */
    @Transactional
    public Map<String, Object> cancelConfirm(Long id, LoginUser user) {
        WorkOrder order = requireOrder(id);
        requireNotArchived(order);
        if (!"PENDING_DEPT_ASSIGN".equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅待分派工单可取消确认");
        }
        String before = order.getStatus();
        // bug修复：原逻辑置 PENDING_DEPT_PROCESS 且清空部门为流转死端（分派入口不接收该状态且部门端无副本）；
        // 管理端撤回部门确认 = 工单整体回管理端分派池，由副本不变式自动删除部门端副本
        order.setStatus(STATUS_PENDING_DISPATCH);
        order.setDepartment(null);
        order.setDeptAuditRemark(null);
        orderCopySyncService.sync(order);
        orderLogService.record(id, "CANCEL_CONFIRM", user.userId(), user.userType(),
                "管理端取消部门确认，工单回退待分派", before, order.getStatus());
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-confirm",
                "取消确认工单：" + order.getOrderNo());
        return Map.of("order_no", order.getOrderNo(), "status", order.getStatus());
    }

    // ============================================================ 6.4 工单等级升级

    /**
     * 等级升级（API 6.4）：待更新工单升级等级后回到已确认；已分派工单同步重算截止时间
     */
    @Transactional
    public Map<String, Object> upgradeOrder(Long id, String newLevel, LoginUser user) {
        WorkOrder order = requireOrder(id);
        requireNotArchived(order);
        if (!"PENDING_UPDATE".equals(order.getStatus()) && !STATUS_PENDING_DISPATCH.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅待更新/待分派工单可执行等级升级");
        }
        if (newLevel.equals(order.getLevel())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "目标等级与当前等级相同，无需升级");
        }
        String beforeLevel = order.getLevel();
        String beforeStatus = order.getStatus();
        order.setLevel(newLevel);
        if ("PENDING_UPDATE".equals(beforeStatus)) {
            order.setStatus("PENDING_DEPT_ASSIGN");
        }
        // 已进入处置流程的工单按新等级重算处理截止时间（需求 21(6)）
        if (order.getDeadline() != null) {
            order.setDeadline(calcDeadline(newLevel, LocalDateTime.now()));
        }
        orderCopySyncService.sync(order);
        orderLogService.record(id, "UPGRADE", user.userId(), user.userType(),
                "工单等级由「" + beforeLevel + "」升级为「" + newLevel + "」",
                Map.of("level", beforeLevel, "status", beforeStatus),
                Map.of("level", order.getLevel(), "status", order.getStatus()));
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-upgrade",
                "工单等级升级：" + order.getOrderNo() + " " + beforeLevel + "→" + newLevel);
        return Map.of("order_no", order.getOrderNo(), "level", newLevel, "status", order.getStatus());
    }

    // ============================================================ 6.5 手动录入 / 6.8 批量导入

    /**
     * 手动录入（API 6.5）：录入后自动执行 AI 智能分类，状态为待确认
     */
    @Transactional
    public Map<String, Object> createOrder(OrderCreateRequest request, LoginUser user) {
        // region 由区县+街道/乡镇+详细地址三段拼接（与市民投诉口径一致），全空时为 null
        String region = PublicAppealService.joinRegion(request.district(), request.street(), request.addressDetail());
        WorkOrder order = createManualOrder(request.title(), request.content(), region, request.level(),
                request.district(), request.street(), request.addressDetail());
        orderLogService.record(order.getId(), "GENERATE", user.userId(), user.userType(),
                "管理员手动录入工单", null, snapshotOf(order));
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-create",
                "手动录入工单：" + order.getOrderNo());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_no", order.getOrderNo());
        data.put("category", order.getCategory());
        data.put("confidence", order.getConfidence());
        data.put("status", order.getStatus());
        return data;
    }

    /**
     * Excel 批量导入（API 6.8）：模板列「诉求标题|诉求内容|所属辖区|工单等级（选填）」，
     * 逐行容错（单行失败不影响其他行），AI 不可用时降级为待人工复核
     */
    @Transactional
    public Map<String, Object> importOrders(MultipartFile file, LoginUser user) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请上传 Excel 文件");
        }
        List<String[]> rows;
        try {
            rows = ExcelUtil.readRows(file.getInputStream());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Excel 文件解析失败：" + e.getMessage());
        }
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Excel 中没有数据行");
        }
        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2; // 跳过表头行，Excel 实际行号
            try {
                String[] cols = rows.get(i);
                String title = cols.length > 0 ? cols[0].trim() : "";
                String content = cols.length > 1 ? cols[1].trim() : "";
                String region = cols.length > 2 ? cols[2].trim() : "";
                String level = cols.length > 3 ? cols[3].trim() : "";
                if (title.isEmpty() || content.isEmpty() || region.isEmpty()) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "诉求标题/内容/辖区为空");
                }
                WorkOrder order = createManualOrder(title, content, region, level);
                orderLogService.record(order.getId(), "GENERATE", user.userId(), user.userType(),
                        "批量导入工单", null, snapshotOf(order));
                success++;
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", rowNo);
                err.put("reason", e.getMessage());
                errors.add(err);
            }
        }
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-import",
                "批量导入工单：成功 " + success + " 条，失败 " + errors.size() + " 条");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success_count", success);
        data.put("fail_count", errors.size());
        data.put("errors", errors);
        return data;
    }

    /**
     * 批量导出工单（API 6.8 对称）：按勾选工单 id 构建导出 Excel（由 Controller 流式回写，零落盘），
     * 表头 5 列「诉求标题|诉求内容|所属辖区|工单等级|工单状态」，前 4 列与导入模板一致可回导，第 5 列记录当前状态
     */
    public XSSFWorkbook exportOrders(List<Long> ids, LoginUser user) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请勾选至少一条工单");
        }
        List<Map<String, Object>> rows = adminWorkOrderMapper.selectExportByIds(ids);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "所选工单不存在或已删除");
        }
        XSSFWorkbook workbook = ExcelUtil.createWorkbook();
        Sheet sheet = workbook.createSheet("工单");
        // 前 4 列对齐导入模板；第 5 列「工单状态」记录当前流转状态中文，导入时自动忽略
        ExcelUtil.writeHeader(sheet, new String[]{"诉求标题", "诉求内容", "所属辖区", "工单等级", "工单状态"});
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            ExcelUtil.writeRow(sheet, i + 1, new Object[]{
                    r.get("title"), r.get("content"), r.get("region"), r.get("level"),
                    STATUS_LABELS_CN.getOrDefault(String.valueOf(r.get("status")), String.valueOf(r.get("status")))
            });
        }
        ExcelUtil.autoSize(sheet, 5);
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-export",
                "批量导出工单：" + rows.size() + " 条");
        return workbook;
    }

    /** 工单状态码 → 管理端中文文案（与前端 ORDER_STATUS 字典一致，导出时服务端翻译） */
    private static final Map<String, String> STATUS_LABELS_CN = Map.ofEntries(
            Map.entry("PENDING_DISPATCH", "待分派"),
            Map.entry("PENDING_UPDATE", "待更新"),
            Map.entry("PENDING_DEPT_PROCESS", "待部门端处理"),
            Map.entry("PENDING_DEPT_ASSIGN", "待部门分派"),
            Map.entry("PENDING_STREET_PROCESS", "待街道办处理"),
            Map.entry("STREET_DONE", "待部门端审核"),
            Map.entry("PENDING_EVALUATE", "待市民评价"),
            Map.entry("STREET_REJECTED", "街道办已驳回"),
            Map.entry("DEPT_REJECTED", "部门已驳回"),
            Map.entry("DEPT_AUDITED", "待终审确认"),
            Map.entry("PENDING_REASSIGN", "待重新分派"),
            Map.entry("CLOSED", "已办结"),
            Map.entry("EVALUATED", "已评价"),
            Map.entry("RETURNED", "已退回")
    );

    /**
     * 诉求自动流转建单（AppealAutoProcessService 调用）：复用 processChild 已算好的分类结果直接落单，
     * 关联来源子诉求ID，并写入工单-子诉求主关联（is_primary=1）
     */
    @Transactional
    public WorkOrder createFromAppeal(AppealChild child, AiClassifyResult classify, String operator) {
        // 幂等预检：该子诉求已关联工单则直接返回既有工单（uk_appeal 唯一约束），避免重复建单
        Long existingOrderId = workOrderAppealMapper.selectOrderIdByAppealId(child.getId());
        if (existingOrderId != null) {
            log.info("子诉求 {} 已关联工单 {}，跳过重复建单", child.getAppealNo(), existingOrderId);
            return adminWorkOrderMapper.selectById(existingOrderId);
        }
        WorkOrder order = createManualOrder(child, classify);
        insertAppealLink(order.getId(), child.getId(), true);
        // 诉求自动建单也记录「工单分类完成」，保证所有工单时间线起点统一（与手动录入/批量导入一致）
        orderLogService.record(order.getId(), "GENERATE", null,
                operator == null || operator.isBlank() ? "SYSTEM" : operator,
                "诉求自动分类完成，生成工单（AI 智能分类）", null, snapshotOf(order));
        // 环节留痕：诉求自动建单（需求：每环节日志记录；操作人=ADMIN-账号名）
        auditLogService.recordOperation(operator == null || operator.isBlank() ? "SYSTEM" : operator, "order-create",
                "诉求自动建单：" + child.getAppealNo() + " → " + order.getOrderNo()
                        + "（类别=" + (order.getCategory() == null ? "无" : order.getCategory()) + "）");
        return order;
    }

    // ============================================================ 第二阶段：去重归并 + 等级判定

    /**
     * 归并目标查找（仅 embedding 语义归并）：使用 processChild 已算好的分类结果取得 category，
     * 查同分类 + 同辖区的近期未归档候选工单，调 /ai/v1/dedup 做向量余弦相似度判定；
     * 无命中返回 null（由调用方走新建工单）
     */
    public WorkOrder findMergeTarget(AppealChild child, AiClassifyResult classify) {
        // 归并粒度（方案C）：区县 + 街道级（严格），不要求门牌地址相同；
        // 区县名与街道名空格拼接（与完整 region 前缀一致，候选 SQL 按前缀匹配仍能命中）
        String county = trimToNull(child.getDistrict());
        String street = trimToNull(child.getStreet());
        String region = (county == null ? null : county.trim())
                + ((county == null || street == null) ? "" : " " + street.trim());
        if (region == null || region.isBlank()) {
            log.info("findMergeTarget 跳过（缺辖区）appealId={}", child.getId());
            return null;
        }
        String category = classify == null ? null : trimToNull(classify.primaryCategory());
        if (category == null) {
            log.info("findMergeTarget 跳过（AI 分类失败/降级）appealId={}", child.getId());
            return null;
        }
        // 归并以二级分类为准：同二级才纳入候选（二级为空的历史数据退化为仅一级匹配）
        String subCategory = classify == null ? null : trimToNull(classify.subCategory());
        if (!enableAiDedup) {
            return null;
        }
        String text = (child.getTitle() == null ? "" : child.getTitle()) + "\n"
                + (child.getContent() == null ? "" : child.getContent());
        LocalDateTime start = LocalDateTime.now().minusDays(dedupWindowDays);
        List<Map<String, Object>> candidates = adminWorkOrderMapper.selectMergeCandidates(category, subCategory, region, start, 20);
        if (candidates.isEmpty()) {
            // 并发建单时前一个工单的 INSERT 可能尚未提交，短暂等待后重试一次
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            candidates = adminWorkOrderMapper.selectMergeCandidates(category, subCategory, region, start, 20);
        }
        log.info("findMergeTarget AI语义归并 appealId={} category={} subCategory={} region={} 候选数={}",
                child.getId(), category, subCategory, region, candidates.size());
        if (candidates.isEmpty()) {
            return null;
        }
        Long matchedId;
        try {
            matchedId = aiDedupMatch(text, candidates);
        } catch (Exception e) {
            // 语义归并失败（AI 不可用/读超时）不阻断建单：降级走新建工单，人工可后续合并
            log.warn("AI 语义归并失败（降级新建）appealId={}：{}", child.getId(), e.getMessage());
            return null;
        }
        if (matchedId != null) {
            log.info("findMergeTarget 语义命中 appealId={} orderId={}", child.getId(), matchedId);
            return adminWorkOrderMapper.selectById(matchedId);
        }
        return null;
    }

    /**
     * 归并子诉求到已有工单：关注度+1、按关注度重算等级、升级若已分派则重算截止时间、写关联表并留痕（事务）
     */
    @Transactional
    public void mergeIntoOrder(Long orderId, AppealChild child, String operator) {
        WorkOrder order = requireOrder(orderId);
        int newCount = (order.getAttentionCount() == null ? 1 : order.getAttentionCount()) + 1;
        String beforeLevel = order.getLevel();
        String newLevel = resolveLevel(newCount);
        order.setAttentionCount(newCount);
        order.setLevel(newLevel);
        if (!newLevel.equals(beforeLevel) && order.getDeadline() != null) {
            order.setDeadline(calcDeadline(newLevel, LocalDateTime.now()));
        }
        orderCopySyncService.sync(order);
        insertAppealLink(orderId, child.getId(), false);
        orderLogService.record(orderId, "MERGE", null, operator,
                "归并同源子诉求「" + child.getAppealNo() + "」，关注度升至 " + newCount, null, null);
        if (!newLevel.equals(beforeLevel)) {
            orderLogService.record(orderId, "MERGE_UPGRADE", null, operator,
                    "关注度累计触发等级升级：" + beforeLevel + " → " + newLevel,
                    Map.of("level", beforeLevel, "attention_count", newCount - 1),
                    Map.of("level", newLevel, "attention_count", newCount));
        }
        auditLogService.recordOperation(operator, "order-merge",
                "归并子诉求 " + child.getAppealNo() + " 至工单 " + order.getOrderNo() + "，等级 " + newLevel);
    }

    /**
     * 子诉求解绑工单（删除子诉求时调用，事务）：关注度-1、按关注度重算等级、
     * 删除关联记录；被删的若是主诉求则自动重指剩余关联子诉求；全程留痕。
     * 调用方需先校验「非唯一工单」（attentionCount > 1）再进入。
     */
    @Transactional
    public void demergeChildAppeal(Long appealId, Long orderId, String operator) {
        WorkOrder order = requireOrder(orderId);
        int curCount = order.getAttentionCount() == null ? 1 : order.getAttentionCount();
        int newCount = Math.max(1, curCount - 1);
        String beforeLevel = order.getLevel();
        String newLevel = resolveLevel(newCount);
        order.setAttentionCount(newCount);
        order.setLevel(newLevel);
        // 被删的若是主诉求，从剩余关联中重选一条（可能为 null：无剩余则置空）
        boolean repointed = false;
        if (appealId.equals(order.getSourceAppealId())) {
            Long another = workOrderAppealMapper.selectAnotherAppealId(orderId, appealId);
            order.setSourceAppealId(another);
            repointed = another != null;
        }
        orderCopySyncService.sync(order);
        workOrderAppealMapper.deleteByAppealId(appealId);
        orderLogService.record(orderId, "DEMERGE", null, operator,
                "子诉求解绑，关注度降至 " + newCount
                        + (repointed ? "，主诉求已重指" : ""), null, null);
        if (!newLevel.equals(beforeLevel)) {
            orderLogService.record(orderId, "MERGE_DOWNGRADE", null, operator,
                    "关注度下降触发等级降级：" + beforeLevel + " → " + newLevel,
                    Map.of("level", beforeLevel, "attention_count", curCount),
                    Map.of("level", newLevel, "attention_count", newCount));
        }
        auditLogService.recordOperation(operator, "order-demerge",
                "子诉求 " + appealId + " 解绑工单 " + order.getOrderNo()
                        + "，关注度 " + newCount + "，等级 " + newLevel);
    }

    /** 关注度 → 工单等级（三级1-2 / 二级3-4 / 一级≥5，阈值可配置） */
    public String resolveLevel(int attentionCount) {
        if (attentionCount >= levelFirstMin) {
            return "一级";
        }
        if (attentionCount >= levelSecondMin) {
            return "二级";
        }
        return "三级";
    }

    /** 写入工单-子诉求关联（is_primary=1 表示建单首条）；幂等：子诉求已关联工单则跳过（uk_appeal 唯一约束） */
    private void insertAppealLink(Long orderId, Long appealId, boolean primary) {
        if (workOrderAppealMapper.countByAppealId(appealId) > 0) {
            log.warn("子诉求 {} 已关联工单，跳过重复关联（拟关联工单 {}）", appealId, orderId);
            return;
        }
        WorkOrderAppeal link = new WorkOrderAppeal();
        link.setOrderId(orderId);
        link.setAppealId(appealId);
        link.setIsPrimary(primary ? 1 : 0);
        workOrderAppealMapper.insert(link);
    }

    /** AI 相似度归并：返回与子诉求语义最相似且达阈值的候选工单 ID，无命中返回 null */
    private Long aiDedupMatch(String text, List<Map<String, Object>> candidates) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            String candText = String.valueOf(c.getOrDefault("title", "")) + "。"
                    + String.valueOf(c.getOrDefault("content", ""));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.get("id"));
            item.put("text", candText);
            items.add(item);
        }
        Optional<AiDedupResult> result = aiServiceClient.dedup(text, dedupThreshold, items);
        if (result.isEmpty()) {
            return null;
        }
        AiDedupResult r = result.get();
        if (Boolean.TRUE.equals(r.matched()) && r.matchedOrderId() != null) {
            return r.matchedOrderId();
        }
        return null;
    }

    /**
     * 创建手动工单（录入/导入共用）：AI 智能分类，状态默认待确认；AI 不可用时降级待人工复核
     */
    private WorkOrder createManualOrder(String title, String content, String region, String level) {
        return createManualOrder(title, content, region, level, null, null, null);
    }

    /** 手动录入工单（结构化地址）：区县/街道/详细地址与子诉求合成工单口径一致，region 由调用方拼接 */
    private WorkOrder createManualOrder(String title, String content, String region, String level,
                                        String district, String street, String addressDetail) {
        Optional<AiClassifyResult> ai = aiServiceClient.classify(null, title + "\n" + content);
        return buildOrder(title, content, region, level, null, ai.orElse(null),
                district, street, addressDetail, null);
    }

    /** 诉求自动流转建单：复用 processChild 已算好的分类结果，并携带源诉求的结构化地址字段入库 */
    private WorkOrder createManualOrder(AppealChild child, AiClassifyResult classify) {
        String region = (child.getBroadFlag() != null && child.getBroadFlag() == 1)
                ? "秦皇岛全市" : child.getRegion();
        return buildOrder(child.getTitle(), child.getContent(), region, null,
                child.getId(), classify,
                child.getDistrict(), child.getStreet(), child.getAddressDetail(), child.getBroadFlag());
    }

    /** 建单核心逻辑（手动 + 自动共用）：标题校验、分类落库、编号、落库；classify 为空表示分类失败降级待人工复核 */
    private WorkOrder buildOrder(String title, String content, String region, String level,
                                 Long sourceAppealId, AiClassifyResult classify,
                                 String countyName, String streetName, String addressDetail, Integer isBroad) {
        if (title.length() > 200) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "诉求标题不能超过200字");
        }
        WorkOrder order = new WorkOrder();
        order.setTitle(title);
        order.setContent(content);
        // region 兜底：诉求/子诉求缺 region 时，用区县+街道+详细地址拼接（joinRegion 同口径），保证辖区列与归并前缀不缺失
        if (region == null || region.isBlank()) {
            region = PublicAppealService.joinRegion(countyName, streetName, addressDetail);
        }
        order.setRegion(region);
        order.setCountyName(countyName);
        order.setCountyCode(countyName == null ? null : regionService.resolveCountyCode(countyName));
        order.setStreetName(streetName);
        // 类别收敛防呆：一级分类只允许 8 大类 + OTHER，历史遗留类别（PROPERTY/LIVELIHOOD 等）归一为 OTHER
        String cat = classify == null ? null : trimToNull(classify.primaryCategory());
        if (cat != null && !"OTHER".equals(cat) && !VALID_PRIMARY_CATEGORIES.contains(cat)) {
            cat = "OTHER";
        }
        // 二级分类归一：空 → "其他"（没有对应二级分类时归为其他）
        String sub = classify == null ? null : trimToNull(classify.subCategory());
        if (cat != null && sub == null) {
            sub = "其他";
        }
        // 承办街道办：二级分类为"其他"时不自动预填（需人工在详情中手动选择街道办后方可分派）；
        // 正常分类时传完整地址（含街道名）匹配 sys_region 下属街道/镇/乡
        order.setStreetOffice("其他".equals(sub) ? null : matchStreetOffice(region));
        order.setAddressDetail(addressDetail);
        order.setIsBroad(isBroad);
        order.setLevel(normalizeLevel(level));
        order.setAttentionCount(1);
        order.setStatus(STATUS_PENDING_DISPATCH);
        order.setSourceAppealId(sourceAppealId);
        if (cat != null) {
            order.setCategory(cat);
            order.setSubCategory(sub);
            // 一级分类为"其他"（OTHER）时不自动映射分派部门（需人工在详情中手动选择部门后才可分派/进入自动派单）；
            // 二级为"其他"不影响部门映射（部门由一级分类决定）
            order.setDepartment("OTHER".equals(cat) ? null : deptDisplay(order, cat));
            order.setConfidence(classify.confidence() == null ? null
                    : BigDecimal.valueOf(classify.confidence()).setScale(4, RoundingMode.HALF_UP));
            order.setHitKeywords(classify.hitKeywords() == null || classify.hitKeywords().isEmpty() ? null
                    : String.join(",", classify.hitKeywords()));
            // 一级/二级为"其他"均需人工介入（选部门/选街道办），标记复核
            order.setNeedReview("OTHER".equals(cat) || "其他".equals(sub)
                    || Boolean.TRUE.equals(classify.needReview()) ? 1 : 0);
        } else {
            order.setNeedReview(1);
        }
        order.setOrderNo(nextOrderNo());
        adminWorkOrderMapper.insert(order);
        // 生成工单自动分派（受「自动派单」开关控制）：开启且分类已确定部门时直接分配至部门端；
        // 手动模式（开关关闭）保持「待分派」由管理端人工分派；分类失败/降级的工单同样待人工处理
        if (isAutoDispatchEnabled()) {
            autoDispatchToDept(order, null);
        }
        return order;
    }

    /**
     * 工单生成自动分派（生成工单时自动分配）：department 非空时直接推进至「待部门端处理」
     * （等价于自动完成管理端「分派至部门」），并记录到达本部门时间、留痕与部门收单审计；
     * 分类未确定部门（降级待复核）时静默跳过，保留 PENDING_DISPATCH 待人工分派。
     */
    private void autoDispatchToDept(WorkOrder order, String operator) {
        if (order.getDepartment() == null || order.getDepartment().isBlank()) {
            return;
        }
        String op = operator == null || operator.isBlank() ? "SYSTEM" : operator;
        java.time.LocalDateTime receiveTime = LocalDateTime.now();
        // 原子领取（CAS）：仅当工单仍处于待分派时推进——防建单事务与 worker 竞态重复分派/重复日志
        int claimed = adminWorkOrderMapper.claimAutoDispatch(order.getId(), receiveTime);
        if (claimed == 0) {
            return; // 已被其他路径分派，放弃（不重复记日志）
        }
        order.setStatus(STATUS_PENDING_DEPT_PROCESS);
        order.setDeptReceiveTime(receiveTime);
        // 三端数据解耦：自动分派与管理端手动分派同路径——事务内刷新部门端副本（不变式：department 非空）
        orderCopySyncService.sync(order);
        orderLogService.record(order.getId(), "AUTO_DISPATCH", null, op,
                "工单生成自动分派至部门：" + order.getDepartment(),
                Map.of("status", "PENDING_DISPATCH", "department", order.getDepartment()),
                Map.of("status", order.getStatus(), "department", order.getDepartment()));
        auditLogService.recordOperation(op, "order-dispatch",
                "工单生成自动分派：" + order.getOrderNo() + " → " + order.getDepartment());
        auditLogService.recordOperation(order.getCategory(), "order-receive",
                "收到工单：" + order.getOrderNo() + "（自动分派）", deptReceiveTarget(order));
        // SSE 实时推送（工单管理页被动刷新：含建单即分派路径）
        sseEventService.emit(SseEventService.TYPE_DISPATCH, "status-change",
                Map.of("orderId", order.getId(), "status", STATUS_PENDING_DEPT_PROCESS));
    }

    /** 自动派单开关（与 AppealAutoProcessService 共用 Redis 键 config:auto_dispatch，避免循环依赖） */
    public boolean isAutoDispatchEnabled() {
        return "true".equals(redisTemplate.opsForValue().get("config:auto_dispatch"));
    }

    /**
     * 按工单执行自动派单（供「自动派单」调度器/手动触发调用）：
     * 仅当工单处于待分派（PENDING_DISPATCH）且已确定分派部门时执行，返回是否完成派单。
     */
    /**
     * 按工单执行自动派单（供「自动派单」调度器/手动触发调用）：
     * 事务加固：CAS 领取 + 副本同步 + 流转日志在同一事务内完成，防止领取后同步失败导致工单滞留中间态
     */
    @Transactional
    public boolean autoDispatchById(Long orderId, String operator) {
        if (orderId == null) {
            return false;
        }
        WorkOrder order = adminWorkOrderMapper.selectById(orderId);
        if (order == null || order.getIsDeleted() != null && order.getIsDeleted() == 1) {
            return false;
        }
        if (!STATUS_PENDING_DISPATCH.equals(order.getStatus())
                || order.getDepartment() == null || order.getDepartment().isBlank()) {
            return false;
        }
        autoDispatchToDept(order, operator);
        return true;
    }

    /** 待分派工单数（自动派单开关状态展示用） */
    public long countPendingDispatch() {
        return adminWorkOrderMapper.countPendingDispatch();
    }

    /** 当前列表筛选条件下（与 countPage 同 where）的待分派工单数：
     *  工单页分派进度分子 = countPendingPage，分母 = countPage（列表总数），
     *  保证分派进度反映「当前筛选列表集合」而非全表，列表为空时前端显示 100% */
    public long countPendingPage(String orderNo, String category, String level,
                                 String region, String status, String keyword, String startDate,
                                 Integer archived) {
        return adminWorkOrderMapper.countPendingPage(orderNo, category, level, region, status, keyword, startDate, archived);
    }

    /** 未删除工单总数（工单页分派完成率分母） */
    public long countAllOrders() {
        return adminWorkOrderMapper.countAllNotDeleted();
    }

    /** 工单编号：WO + 日期 + 当日3位序号（如 WO20260824001） */
    private String nextOrderNo() {
        String prefix = "WO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = adminWorkOrderMapper.countByPrefix(prefix) + 1;
        return prefix + String.format("%03d", seq);
    }

    /** 等级规范化：一级/二级/三级，默认三级 */
    private String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return "三级";
        }
        return switch (level.trim()) {
            case "一级", "二级", "三级" -> level.trim();
            default -> throw new BusinessException(ResultCode.PARAM_ERROR, "工单等级必须为一级/二级/三级之一");
        };
    }

    /** 空白字符串归一化为 null（去重归并键预处理） */
    private String trimToNull(String s) {
        return s == null ? null : (s.isBlank() ? null : s.trim());
    }

    // ============================================================ 6.6 编辑工单

    /**
     * 编辑工单（API 6.6）：修改内容/辖区/分类，修改前后数据留痕；分类变更同步重算分派部门；
     * 支持详情弹窗手动指定分派部门（department，解析区县+分类联动）与承办街道办（street_office）
     */
    @Transactional
    public Map<String, Object> updateOrder(Long id, OrderUpdateRequest request, LoginUser user) {
        WorkOrder order = requireOrder(id);
        requireNotArchived(order);
        Map<String, Object> before = snapshotOf(order);
        if (request.title() != null && !request.title().isBlank()) {
            order.setTitle(request.title().trim());
        }
        if (request.content() != null && !request.content().isBlank()) {
            order.setContent(request.content().trim());
        }
        if (request.region() != null && !request.region().isBlank()) {
            order.setRegion(request.region().trim());
            // 辖区变更联动区县重算（工单区县为空时补齐，保证部门端按区县隔离可见）
            String county = normalizeCounty(order.getRegion());
            if (county != null) {
                order.setCountyName(county);
                order.setCountyCode(regionService.resolveCountyCode(county));
            }
            // 分好辖区后预填承办街道办（region 匹配街道办账号的 region）；二级分类为"其他"时不预填（待人工选择）
            if (!"其他".equals(order.getSubCategory())) {
                order.setStreetOffice(matchStreetOffice(request.region().trim()));
            }
        }
        if (request.subCategory() != null) {
            String sub = request.subCategory().isBlank() ? null : request.subCategory().trim();
            if (sub == null) {
                // 二级分类归一：一级分类已确定而二级被清空 → "其他"（没有对应二级分类时归为其他）
                String effectiveCat = request.category() != null && !request.category().isBlank()
                        ? request.category().trim() : order.getCategory();
                if (effectiveCat != null && !effectiveCat.isBlank()) {
                    sub = "其他";
                }
            }
            boolean wasSubOther = "其他".equals(order.getSubCategory());
            boolean nowSubOther = "其他".equals(sub);
            // 二级分类为"其他" → 承办街道办置空待人工选择；从"其他"改回正常标签 → 按辖区恢复自动匹配
            if (nowSubOther) {
                order.setStreetOffice(null);
            } else if (wasSubOther) {
                order.setStreetOffice(matchStreetOffice(order.getRegion()));
            }
            order.setSubCategory(sub);
        }
        if (request.category() != null && !request.category().isBlank()) {
            order.setCategory(request.category());
            // 分类变更同步智能分派部门（需求 12(1)：分类与部门一一对应）；
            // 一级分类为"其他"（OTHER）时不自动映射（需人工在详情中手动选择分派部门后才可分派）；二级"其他"不影响部门映射
            if (!"OTHER".equals(order.getCategory()) && (order.getDepartment() != null || STATUS_PENDING_DEPT_ASSIGN.equals(order.getStatus())
                    || STATUS_PENDING_DISPATCH.equals(order.getStatus()))) {
                order.setDepartment(deptDisplay(order, order.getCategory()));
            }
        }
        // 详情弹窗「处理部门」下拉：手动指定分派部门（一级/二级分类为"其他"时无自动映射，需人工选择后才可分派/进入自动派单）
        if (request.department() != null) {
            applyDepartment(order, request.department().trim());
        }
        // 详情弹窗「承办街道办」下拉：手动指定/修正承办街道办（纯街道名口径）
        if (request.streetOffice() != null) {
            order.setStreetOffice(request.streetOffice().isBlank() ? null : request.streetOffice().trim());
        }
        orderCopySyncService.sync(order);
        orderLogService.record(id, "EDIT", user.userId(), user.userType(),
                "管理员编辑工单", before, snapshotOf(order));
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-edit", "编辑工单：" + order.getOrderNo());
        return Map.of("order_no", order.getOrderNo(), "status", order.getStatus());
    }

    // ============================================================ 6.7 删除工单

    /**
     * 删除工单（API 6.7）：软删除（逻辑删除，is_deleted=1，历史可追溯）。
     * 仅「待分派（PENDING_DISPATCH）」工单可删除——一旦分派（含部门处理中/驳回/已办结/归档）不可删除。
     */
    @Transactional
    public Map<String, Object> deleteOrder(Long id, LoginUser user) {
        WorkOrder order = requireOrder(id);
        requireNotArchived(order);
        // 已分派（含驳回/归档回退的 PENDING_REASSIGN 等）一律不可删除
        if (!STATUS_PENDING_DISPATCH.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "工单已分派，不可删除");
        }
        adminWorkOrderMapper.deleteById(id);
        // 三端数据解耦：物理删除部门端/街道办端副本（管理端逻辑删除保留历史可追溯）
        orderCopySyncService.deleteCopies(id);
        orderLogService.record(id, "DELETE", user.userId(), user.userType(),
                "管理员删除工单（逻辑删除）", snapshotOf(order), null);
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-delete", "删除工单：" + order.getOrderNo());
        return Map.of("order_no", order.getOrderNo(), "deleted", true);
    }

    // ============================================================ 6.9 分类人工修正

    /**
     * 分类人工修正（API 6.9）：修正记录沉淀为纠错样本反哺算法，同步重算分派部门
     */
    @Transactional
    public Map<String, Object> correctCategory(Long id, OrderClassifyCorrectRequest request, LoginUser user) {
        WorkOrder order = requireOrder(id);
        requireNotArchived(order);
        String beforeCategory = order.getCategory();
        String beforeDept = order.getDepartment();
        order.setCategory(request.category());
        order.setNeedReview(0); // 人工已复核
        // 一级分类为"其他"（OTHER）时不自动映射分派部门（需人工在详情中手动选择后才可分派）；二级"其他"不影响部门映射
        if (!"OTHER".equals(order.getCategory()) && (beforeDept != null || STATUS_PENDING_DEPT_ASSIGN.equals(order.getStatus())
                || STATUS_PENDING_DISPATCH.equals(order.getStatus()))) {
            order.setDepartment(deptDisplay(order, order.getCategory()));
        }
        orderCopySyncService.sync(order);
        orderLogService.record(id, "CLASSIFY_CORRECT", user.userId(), user.userType(),
                "分类人工修正：" + beforeCategory + " → " + request.category()
                        + (request.reason() == null || request.reason().isBlank() ? "" : "（" + request.reason() + "）"),
                Map.of("category", beforeCategory == null ? "" : beforeCategory,
                        "department", beforeDept == null ? "" : beforeDept),
                Map.of("category", order.getCategory(),
                        "department", order.getDepartment() == null ? "" : order.getDepartment()));
        auditLogService.recordOperation(user.userType() + "-" + user.jobNo(), "order-classify-correct",
                "分类人工修正：" + order.getOrderNo() + " " + beforeCategory + "→" + request.category());
        return Map.of("order_no", order.getOrderNo(), "category", order.getCategory());
    }

    /** 工单关键字段快照（修改前后数据留痕） */
    private Map<String, Object> snapshotOf(WorkOrder order) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_no", order.getOrderNo());
        m.put("title", order.getTitle());
        m.put("content", order.getContent());
        m.put("category", order.getCategory());
        m.put("sub_category", order.getSubCategory());
        m.put("level", order.getLevel());
        m.put("region", order.getRegion());
        m.put("county_name", order.getCountyName());
        m.put("department", order.getDepartment());
        m.put("street_office", order.getStreetOffice());
        m.put("status", order.getStatus());
        return m;
    }
}