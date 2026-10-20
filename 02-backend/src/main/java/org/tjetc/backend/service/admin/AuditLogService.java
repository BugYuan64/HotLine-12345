package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.entity.admin.SysAuditLog;
import org.tjetc.backend.mapper.admin.AdminAuditLogMapper;
import org.tjetc.backend.mapper.dept.DeptAuditLogMapper;
import org.tjetc.backend.mapper.street.StreetAuditLogMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统审计服务（需求 20 系统日志与安全审计 / API 8.6）
 * - AI 生成内容显式标识"AI生成·置信度X%"并记录决策依据（API 2.5 安全规范）
 * - 仅 INSERT/SELECT，审计日志不可删除/修改，永久留存
 *
 * 三端数据解耦后的日志路由（写入端 = 归属端）：
 *   admin_audit_log  ：管理端操作（operator=ADMIN-*）、AI 生成、系统日志
 *   dept_audit_log   ：部门端账号操作（operator=URBAN-xxx 等）+ 归属部门的「收到工单」（target_type=部门代号）
 *   street_audit_log ：街道办端账号操作（operator=STREET-*）+ 归属街道办的「收到工单」（target_type=STREET:区县）
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    public static final String TYPE_LOGIN = "LOGIN";
    public static final String TYPE_OPERATION = "OPERATION";
    public static final String TYPE_ERROR = "ERROR";
    public static final String TYPE_AI_GENERATION = "AI_GENERATION";
    /** AI 大模型标识（操作人显示为 AI-qwen-plus 等） */
    public static final String AI_MODEL = "qwen-plus";

    private final AdminAuditLogMapper adminAuditLogMapper;
    private final DeptAuditLogMapper deptAuditLogMapper;
    private final StreetAuditLogMapper streetAuditLogMapper;
    private final org.tjetc.backend.mapper.common.SysUserMapper sysUserMapper;
    private final org.tjetc.backend.service.common.RegionService regionService;

    /** 部门端 user_type 清单（8 大分派部门 + 保留物业/民生）：写入路由与 deptPage 工号清单共用 */
    private static final List<String> DEPT_USER_TYPES = List.of(
            "URBAN", "PROPERTY", "LIVELIHOOD",
            "TRAFFIC", "MARKET", "ENVIRONMENT", "HOUSING", "LABOR", "PUBLIC", "HEALTH");

    /** 部门端账号工号清单（operator 精确匹配用，前缀=部门代号）：dept 指定时仅该部门账号，null 时全部部门账号；
     *  countyCode 指定时仅该区县账号（56=7区县×8分类口径；null 时不过滤区县） */
    private List<String> deptJobNos(String dept, String countyCode) {
        List<String> nos = new java.util.ArrayList<>();
        java.util.function.Consumer<org.tjetc.backend.entity.common.SysUser> add = u -> {
            if (countyCode == null || countyCode.isBlank() || countyCode.equals(u.getRegionCode())) {
                nos.add(u.getUserType() + "-" + u.getJobNo());
            }
        };
        if (dept != null && !dept.isBlank() && DEPT_USER_TYPES.contains(dept)) {
            sysUserMapper.selectEnabledByUserType(dept).forEach(add);
            return nos;
        }
        for (String type : DEPT_USER_TYPES) {
            sysUserMapper.selectEnabledByUserType(type).forEach(add);
        }
        return nos;
    }

    /**
     * 区县名 → 该区县下的街道名集合（供街道办审计按区县过滤）：
     * county 为 null/空 时返回 null 表示不过滤；county 查不到对应区县时返回空集合（结果为空，不抛错）。
     */
    private java.util.Set<String> streetNamesOfCounty(String county) {
        if (county == null || county.isBlank()) {
            return null;
        }
        String countyCode = regionService.resolveCountyCode(county);
        if (countyCode == null) {
            return java.util.Collections.emptySet();
        }
        return regionService.listChildren(countyCode).stream()
                .map(org.tjetc.backend.entity.common.SysRegion::getRegionName)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 部门审计（管理端视角）：读 dept_audit_log——部门端各账号的操作 + 归属该部门的「收到工单」记录。
     * dept=部门代号：只看该部门；null：全部部门端记录。
     */
    public PageData<Map<String, Object>> deptPage(String logType, String module,
                                                  Long id, String dept, String countyCode,
                                                  LocalDateTime startTime, LocalDateTime endTime,
                                                  int page, int pageSize, String appealKeyword) {
        int offset = Math.max(0, (page - 1) * pageSize);
        List<String> jobNos = deptJobNos(dept, countyCode);
        List<Map<String, Object>> rows = deptAuditLogMapper.selectDeptPage(
                logType, module, id, dept, countyCode, jobNos, startTime, endTime, appealKeyword, offset, pageSize);
        long total = deptAuditLogMapper.countDeptPage(
                logType, module, id, dept, countyCode, jobNos, startTime, endTime, appealKeyword);
        return PageData.of(mapRows(rows), total, page, pageSize);
    }

    /**
     * 街道办审计（管理端视角）：读 street_audit_log——所有街道办账号的操作 + 归属街道办的「收到工单」记录。
     * county=区县名：只看该区县街道办；null：全部街道办端记录。
     */
    public PageData<Map<String, Object>> streetPage(String logType, String module,
                                                    Long id, String county,
                                                    LocalDateTime startTime, LocalDateTime endTime,
                                                    int page, int pageSize, String appealKeyword) {
        int offset = Math.max(0, (page - 1) * pageSize);
        // 街道办账号（STREET-*，可按区县过滤：county 为区县名，经区划表反查该区县下街道才能与账号 region（街道名）匹配）
        java.util.List<String> jobNos = new java.util.ArrayList<>();
        java.util.Set<String> streetNames = streetNamesOfCounty(county);
        sysUserMapper.selectEnabledByUserType("STREET").forEach(u -> {
            if (streetNames != null && !streetNames.contains(u.getRegion())) {
                return;
            }
            jobNos.add(u.getUserType() + "-" + u.getJobNo());
        });
        String targetPattern = county != null && !county.isBlank() ? "STREET:" + county + "%" : "STREET%";
        List<Map<String, Object>> rows = streetAuditLogMapper.selectStreetPage(
                logType, module, id, startTime, endTime, jobNos, targetPattern, appealKeyword, offset, pageSize);
        long total = streetAuditLogMapper.countStreetPage(logType, module, id, startTime, endTime, jobNos, targetPattern, appealKeyword);
        return PageData.of(mapRows(rows), total, page, pageSize);
    }

    /**
     * 系统日志与 AI 生成内容审计查询（API 8.6，仅查询）：管理端审计日志（admin_audit_log）
     */
    public PageData<Map<String, Object>> page(String logType, String operator, String module,
                                              Long id, LocalDateTime startTime, LocalDateTime endTime,
                                              int page, int pageSize) {
        return page(logType, operator, module, id, startTime, endTime, page, pageSize, null, null, null);
    }

    /**
     * 系统审计查询（带归属端范围）：
     * - scopeMode=null/ADMIN：管理端视角，读 admin_audit_log（scopeMode=ADMIN 时排除「收到工单」类，防御性保留）
     * - scopeMode=STAFF：部门端视角，读 dept_audit_log = 自己的操作 + 归属本部门的「收到工单」记录
     * - scopeMode=STREET：街道办端视角，读 street_audit_log = 自己的操作 + 归属本街道的「收到工单」记录
     */
    public PageData<Map<String, Object>> page(String logType, String operator, String module,
                                              Long id, LocalDateTime startTime, LocalDateTime endTime,
                                              int page, int pageSize, String scopeMode, String scopeTarget,
                                              String appealKeyword) {
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> rows;
        long total;
        if ("STAFF".equals(scopeMode) || "STREET".equals(scopeMode)) {
            // scopeTarget 形如 "STAFF:URBAN-urban001:URBAN" / "STREET:STREET-street001:STREET:海港区"，拆出三段
            String scopeAccount = null;
            String targetMatch = null;
            if (scopeTarget != null && scopeTarget.contains(":")) {
                String[] parts = scopeTarget.split(":", 3);
                scopeAccount = parts.length > 1 ? parts[1] : null;
                targetMatch = parts.length > 2 ? parts[2] : null;
            }
            if ("STREET".equals(scopeMode)) {
                rows = streetAuditLogMapper.selectOwnPage(logType, module, id, startTime, endTime,
                        scopeAccount, targetMatch, appealKeyword, offset, pageSize);
                total = streetAuditLogMapper.countOwnPage(logType, module, id, startTime, endTime,
                        scopeAccount, targetMatch, appealKeyword);
            } else {
                rows = deptAuditLogMapper.selectOwnPage(logType, module, id, startTime, endTime,
                        scopeAccount, targetMatch, appealKeyword, offset, pageSize);
                total = deptAuditLogMapper.countOwnPage(logType, module, id, startTime, endTime,
                        scopeAccount, targetMatch, appealKeyword);
            }
        } else {
            // 管理端视角：「收到工单」类日志已归入部门/街道端日志表，无需排除（防御性保留 excludeModule）
            String excludeModule = "ADMIN".equals(scopeMode) ? "order-receive" : null;
            rows = adminAuditLogMapper.selectPage(logType, operator, module, id, startTime, endTime,
                    excludeModule, appealKeyword, offset, pageSize);
            total = adminAuditLogMapper.countPage(logType, operator, module, id, startTime, endTime, excludeModule, appealKeyword);
        }
        return PageData.of(mapRows(rows), total, page, pageSize);
    }

    /** 统一 snake_case 键名转换（API 8.6 响应字段） */
    private List<Map<String, Object>> mapRows(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("log_type", v(r, "logType", "log_type"));
            m.put("operator", r.get("operator"));
            m.put("module", r.get("module"));
            m.put("content_type", v(r, "contentType", "content_type"));
            m.put("summary", r.get("summary"));
            m.put("confidence", r.get("confidence"));
            m.put("decision_basis", v(r, "decisionBasis", "decision_basis"));
            m.put("model", r.get("model"));
            m.put("target_type", r.get("target_type"));
            m.put("create_time", v(r, "createTime", "create_time"));
            return m;
        }).toList();
    }

    private Object v(Map<String, Object> m, String camel, String snake) {
        // 执行v操作
        if (m.containsKey(camel)) {
            return m.get(camel);
        }
        return m.get(snake);
    }

    /**
     * AI 生成内容审计（summary 自带"AI生成·置信度X%"标识，决策依据留痕）——写入管理端审计日志
     * 操作人统一为 "AI"（不区分模型；模型标识落在 model 字段）
     */
    public void recordAiGeneration(String contentType, String summary, Double confidence,
                                   String decisionBasis, String model) {
        // 执行recordAiGeneration操作
        SysAuditLog log = new SysAuditLog();
        log.setLogType(TYPE_AI_GENERATION);
        log.setOperator("AI");
        log.setModule("ai_generation");
        log.setContentType(contentType);
        log.setSummary(summary);
        log.setConfidence(confidence == null ? null : BigDecimal.valueOf(confidence));
        log.setDecisionBasis(decisionBasis);
        log.setModel(model);
        adminAuditLogMapper.insert(log);
    }

    /**
     * 操作审计（管理员操作留痕，如账号审核/工单审核/角色分配等）
     */
    public void recordOperation(String operator, String module, String summary) {
        recordOperation(operator, module, summary, null);
    }

    /**
     * 操作审计（带归属端，三端数据解耦后按归属端路由写入对应端日志表）：
     * 路由规则（与迁移脚本 30-split-three-ends.sql 一致）：
     *   operator 以 STREET 开头 或 targetType 以 STREET 开头 → street_audit_log；
     *   operator 为部门代号（URBAN-xxx / 纯代号）或「收到工单」归属部门（targetType=部门代号）→ dept_audit_log；
     *   其余（管理端账号 / AI / 系统日志）→ admin_audit_log。
     */
    public void recordOperation(String operator, String module, String summary, String targetType) {
        SysAuditLog log = new SysAuditLog();
        log.setLogType(TYPE_OPERATION);
        log.setOperator(operator);
        log.setModule(module);
        log.setSummary(summary);
        log.setTargetType(targetType);
        insertRouted(log, operator, targetType);
    }

    /** 按操作人/归属端路由写入对应端审计日志表 */
    private void insertRouted(SysAuditLog log, String operator, String targetType) {
        boolean street = (operator != null && operator.startsWith("STREET"))
                || (targetType != null && targetType.startsWith("STREET"));
        if (street) {
            streetAuditLogMapper.insert(log);
            return;
        }
        boolean deptByOperator = operator != null && DEPT_USER_TYPES.stream()
                .anyMatch(t -> operator.equals(t) || operator.startsWith(t + "-"));
        // 「收到工单」类日志 target_type=部门代号（无 STREET 前缀）→ 归入部门端日志表
        boolean deptByTarget = targetType != null && !targetType.isBlank();
        if (deptByOperator || deptByTarget) {
            deptAuditLogMapper.insert(log);
            return;
        }
        adminAuditLogMapper.insert(log);
    }

    /**
     * 拆分父诉求审计（AI 执行拆分，操作人统一 "AI"；返回日志ID供子诉求关联 clean_log_id）——管理端日志
     */
    public long recordAppealSplit(String operator, String summary, String decisionBasis) {
        SysAuditLog log = new SysAuditLog();
        log.setLogType(TYPE_OPERATION);
        log.setOperator("AI");
        log.setModule("appeal-split");
        log.setContentType("SPLIT");
        log.setSummary(summary);
        log.setDecisionBasis(decisionBasis);
        adminAuditLogMapper.insert(log);
        return log.getId();
    }

    /**
     * 清洗子诉求审计（AI 执行清洗，操作人统一 "AI"；返回日志ID供子诉求关联 clean_log_id）——管理端日志
     */
    public long recordAppealClean(String operator, String summary, String decisionBasis) {
        SysAuditLog log = new SysAuditLog();
        log.setLogType(TYPE_OPERATION);
        log.setOperator("AI");
        log.setModule("appeal-clean");
        log.setContentType("CLEAN");
        log.setSummary(summary);
        log.setDecisionBasis(decisionBasis);
        adminAuditLogMapper.insert(log);
        return log.getId();
    }
}
