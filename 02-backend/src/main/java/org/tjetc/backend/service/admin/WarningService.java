package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.admin.WarningRecordReviewRequest;
import org.tjetc.backend.dto.admin.WarningRuleFromPredictionRequest;
import org.tjetc.backend.dto.admin.WarningRuleRequest;
import org.tjetc.backend.dto.ai.AiCaseSearchItem;
import org.tjetc.backend.dto.ai.AiCaseSearchResult;
import org.tjetc.backend.dto.ai.AiWarningMeasureResult;
import org.tjetc.backend.dto.ai.AiWarningSummaryResult;
import org.tjetc.backend.entity.common.SysMessage;
import org.tjetc.backend.entity.common.SysRegion;
import org.tjetc.backend.entity.common.SysUser;
import org.tjetc.backend.entity.admin.ClassifyPrimary;
import org.tjetc.backend.entity.admin.PredictionResult;
import org.tjetc.backend.entity.admin.WarningRecord;
import org.tjetc.backend.entity.admin.WarningRule;
import org.tjetc.backend.mapper.common.SysMessageMapper;
import org.tjetc.backend.mapper.common.SysUserMapper;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;
import org.tjetc.backend.mapper.admin.ClassifyPrimaryMapper;
import org.tjetc.backend.mapper.admin.WarningRecordMapper;
import org.tjetc.backend.mapper.admin.WarningRuleMapper;
import org.tjetc.backend.mapper.admin.WarningStreetDispatchMapper;
import org.tjetc.backend.service.common.AiServiceClient;
import org.tjetc.backend.service.common.RegionService;
import org.tjetc.backend.service.common.SmsSender;


import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 预警配置管理（需求 11 预警配置管理 / API 8.1/8.2/10.9/10.10）：
 * - 预警规则 CRUD（新增/编辑/启停/删除，停用后不再触发）
 * - 定时扫描：命中规则（时间维度窗口内工单数 >= 阈值）生成预警记录，
 *   调 AI 生成预警总结与应对措施（不可用则降级为空，不阻断预警入库），
 *   按推送对象归属类型推送站内消息，全程审计留痕
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarningService {

    /** 时间维度对应天数（API 8.1：DAY/3_DAY/7_DAY） */
    private static final Map<String, Integer> DIMENSION_DAYS = Map.of("DAY", 1, "3_DAY", 3, "7_DAY", 7);
    /** 预警等级文案（API 14.1） */
    private static final Map<String, String> LEVEL_LABELS =
            Map.of("GENERAL", "一般", "SEVERE", "较重", "URGENT", "紧急");
    /** 站内消息类型：预警推送（API 14.1 消息类型未定义预警场景，按需求 11(3) 补充约定） */
    public static final String MSG_TYPE_WARNING = "WARNING";

    /** 8 大分派部门 code（推送对象/复合键校验，56=7区县×8分类口径） */
    private static final Set<String> DEPT_PUSH_CODES = Set.of(
            "URBAN", "TRAFFIC", "MARKET", "ENVIRONMENT", "HOUSING", "LABOR", "PUBLIC", "HEALTH");

    private final WarningRuleMapper ruleMapper;
    private final ClassifyPrimaryMapper classifyPrimaryMapper;
    private final WarningRecordMapper recordMapper;
    private final WarningStreetDispatchMapper dispatchMapper;
    private final SysMessageMapper messageMapper;
    private final SysUserMapper userMapper;
    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final AiServiceClient aiServiceClient;
    private final SmsSender smsSender;
    private final AuditLogService auditLogService;
    private final RegionService regionService;


    public List<Map<String, Object>> rules() {
        // 查询规则列表
        return ruleMapper.selectAll().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("rule_name", r.getRuleName());
            m.put("time_dimension", r.getTimeDimension());
            m.put("category", r.getCategory());
            m.put("sub_category", r.getSubCategory());
            m.put("region", r.getRegion());
            m.put("count_threshold", r.getCountThreshold());
            m.put("warning_level", r.getWarningLevel());
            m.put("push_targets", splitTargets(r.getPushTargets()));
            m.put("push_counties", splitTargets(r.getPushCounties()));
            m.put("push_categories", splitTargets(r.getPushCategories()));
            m.put("status", r.getStatus());
            m.put("create_time", r.getCreateTime());
            return m;
        }).toList();
    }

    public Map<String, Object> addRule(WarningRuleRequest request) {
        // 添加规则
        WarningRule rule = toRule(new WarningRule(), request);
        rule.setStatus(request.status() == null ? "ENABLED" : request.status());
        ruleMapper.insert(rule);
        auditLogService.recordOperation("ADMIN", "warning-rule", "新增预警规则：" + rule.getRuleName());
        return Map.of("rule_id", rule.getId());
    }

    /**
     * 预判结果一键生成预警规则草稿（DRAFT）：字段由预判热点回填，
     * 生成后出现在预警规则列表，默认不参与扫描，需人工编辑确认后启用。
     */
    public Map<String, Object> createRuleFromPrediction(WarningRuleFromPredictionRequest request) {
        // 预判热点生成草稿规则（DRAFT）：字段由预判热点回填，规则名/阈值/等级按方案 7 换算
        WarningRule rule = new WarningRule();
        String region = blank(request.region());
        String category = blank(request.category());
        String sub = blank(request.subCategory());
        String nameTarget = !sub.isEmpty() ? sub : (!category.isEmpty() ? primaryName(category) : "全分类");
        rule.setRuleName(buildPredictionRuleName(region, nameTarget, request.period()));
        rule.setTimeDimension(toTimeDimension(request.period()));
        rule.setCategory(category.isEmpty() ? null : category);
        rule.setSubCategory(sub.isEmpty() ? null : sub);
        rule.setRegion(region.isEmpty() ? null : region);
        // 数量阈值：预测量取 0.8 倍（预留缓冲，避免临界频繁触发）
        int threshold = request.predictedCount() == null ? 1
                : Math.max(1, (int) Math.round(request.predictedCount() * 0.8));
        rule.setCountThreshold(threshold);
        rule.setWarningLevel(levelFromPrediction(request.predictedCount(), request.confidence()));
        rule.setPushTargets(null);
        // 推送对象默认回填预判筛选口径（只参考一级分类）：类别=该一级分类，辖区=筛选区县（空=不限）
        rule.setPushCategories(category.isEmpty() ? null : category);
        rule.setPushCounties(region.isEmpty() ? null : regionService.resolveCountyCode(region));
        rule.setStatus("DRAFT");
        ruleMapper.insert(rule);
        auditLogService.recordOperation("ADMIN", "warning-rule",
                "预判生成草稿规则：" + rule.getRuleName() + "（阈值" + threshold + "，等级" + rule.getWarningLevel() + "）");
        return Map.of("rule_id", rule.getId(), "rule_name", rule.getRuleName(), "status", rule.getStatus());
    }

    public Map<String, Object> updateRule(Long id, WarningRuleRequest request) {
        // 更新规则
        WarningRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "预警规则不存在");
        }
        toRule(rule, request);
        if (request.status() != null) {
            rule.setStatus(request.status());
        }
        ruleMapper.update(rule);
        auditLogService.recordOperation("ADMIN", "warning-rule",
                "编辑预警规则：" + rule.getRuleName() + "（状态" + rule.getStatus() + "）");
        return Map.of("rule_id", id);
    }

    public Map<String, Object> deleteRule(Long id) {
        // 删除规则
        if (ruleMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "预警规则不存在");
        }
        ruleMapper.delete(id);
        auditLogService.recordOperation("ADMIN", "warning-rule", "删除预警规则 id=" + id);
        return Map.of("deleted", true);
    }

    /** 二级分类建议列表（API 8.1）：按一级分类返回已用到的二级分类，供前端可选用 */
    public List<String> listSubCategories(String category) {
        return adminWorkOrderMapper.selectDistinctSubCategory(category);
    }

    /** 标记预警已响应（需求 11(4)）：关闭响应闭环，置 RESPONDED 并记录响应时间 */
    public Map<String, Object> respond(Long id) {
        // 标记Respond
        if (recordMapper.updateRespond(id) == 0) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "预警记录不存在");
        }
        // 管理端响应后反向同步分发表：已分发的街道办记录全部置为已响应（街道办端列表读分发表）
        dispatchMapper.updateAllRespond(id, LocalDateTime.now());
        auditLogService.recordOperation("ADMIN", "warning-respond", "标记预警已响应 id=" + id);
        return Map.of("responded", true);
    }

    /** 审核发送预警（方案B）：PENDING → SENT，随后才向目标部门推送站内消息 */
    public Map<String, Object> send(Long id) {
        // 发送预警
        WarningRecord record = requirePending(id);
        recordMapper.updateStatus(id, "SENT", LocalDateTime.now());
        WarningRule rule = ruleMapper.selectById(record.getRuleId());
        if (rule != null) {
            pushMessages(rule, record);
        } else {
            log.warn("预警 {} 关联规则不存在，跳过站内推送", record.getWarningNo());
        }
        auditLogService.recordOperation("ADMIN", "warning-review", "发送预警：" + record.getWarningNo());
        return Map.of("sent", true);
    }

    /** 审核驳回预警（方案B）：PENDING → REJECTED，保留备查 */
    public Map<String, Object> reject(Long id) {
        // 驳回预警
        WarningRecord record = requirePending(id);
        recordMapper.updateStatus(id, "REJECTED", LocalDateTime.now());
        auditLogService.recordOperation("ADMIN", "warning-review", "驳回预警：" + record.getWarningNo());
        return Map.of("rejected", true);
    }

    /** 审核中编辑 AI 总结与应对措施（方案B）：仅待审核（PENDING）记录可修订 */
    public Map<String, Object> updateContent(Long id, WarningRecordReviewRequest request) {
        // 编辑预警内容
        WarningRecord record = requirePending(id);
        recordMapper.updateContent(id, request.aiSummary(), request.measure());
        auditLogService.recordOperation("ADMIN", "warning-review", "编辑预警内容：" + record.getWarningNo());
        return Map.of("updated", true);
    }

    /** 从预警记录生成预警规则草稿（方案B）：驳回记录备查 + 二次利用，默认不参与扫描 */
    public Map<String, Object> generateRuleFromRecord(Long id) {
        // 记录生成草稿规则
        WarningRecord record = recordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "预警记录不存在");
        }
        WarningRule rule = new WarningRule();
        String nameTarget = record.getSubCategory() != null && !record.getSubCategory().isBlank()
                ? record.getSubCategory()
                : (record.getCategory() != null ? primaryName(record.getCategory()) : "全分类");
        StringBuilder name = new StringBuilder();
        if (record.getRegion() != null && !record.getRegion().isBlank()) {
            name.append(record.getRegion()).append("·");
        }
        name.append(nameTarget).append("·预警记录衍生规则");
        rule.setRuleName(name.toString());
        rule.setTimeDimension("7_DAY");
        rule.setCategory(record.getCategory());
        rule.setSubCategory(record.getSubCategory());
        rule.setRegion(record.getRegion());
        rule.setCountThreshold(Math.max(1, record.getRelatedAttention() == null ? 1 : record.getRelatedAttention()));
        rule.setWarningLevel(record.getWarningLevel());
        rule.setPushTargets(record.getDepartment());
        rule.setStatus("DRAFT");
        ruleMapper.insert(rule);
        auditLogService.recordOperation("ADMIN", "warning-rule",
                "预警记录生成草稿规则：" + rule.getRuleName() + "（来源预警 " + record.getWarningNo() + "）");
        return Map.of("rule_id", rule.getId(), "rule_name", rule.getRuleName(), "status", rule.getStatus());
    }

    /** 取待审核记录并校验状态：非 PENDING 不可发送/驳回/编辑 */
    private WarningRecord requirePending(Long id) {
        // 校验待审核状态
        WarningRecord record = recordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "预警记录不存在");
        }
        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅待审核状态的预警可执行该操作");
        }
        return record;
    }

    /**
     * 紧急预警超时未响应升级提醒（B3）：每 6 小时扫描超过 24 小时未响应的 URGENT 预警，
     * 向 ADMIN 追加推送一条强提醒站内信，留痕升级动作
     */
    @Scheduled(cron = "${app.warning.escalate-cron:0 0 */6 * * ?}")
    public void escalateUnhandledUrgent() {
        // 执行escalateUnhandledUrgent操作
        List<Map<String, Object>> overdue = recordMapper.selectUnhandledUrgentOlderThan(LocalDateTime.now().minusHours(24));
        if (overdue.isEmpty()) {
            return;
        }
        List<SysUser> admins = userMapper.selectEnabledByUserType("ADMIN");
        int pushed = 0;
        for (Map<String, Object> row : overdue) {
            String no = String.valueOf(row.get("warning_no"));
            for (SysUser user : admins) {
                SysMessage message = new SysMessage();
                message.setUserId(user.getId());
                message.setMsgType(MSG_TYPE_WARNING);
                message.setTitle("【紧急预警超时未响应】" + no);
                message.setContent("紧急预警 " + no + " 已超过24小时未响应，请及时处置并标记处理。");
                message.setIsRead(0);
                message.setChannel("IN_APP");
                messageMapper.insert(message);
                pushed++;
            }
            auditLogService.recordOperation("SYSTEM", "warning-escalate", "紧急预警超时升级提醒：" + no);
        }
        log.info("紧急预警升级提醒：超时未响应{}条，向ADMIN推送{}条", overdue.size(), pushed);
    }

    private WarningRule toRule(WarningRule rule, WarningRuleRequest request) {
        // 转换为规则对象
        rule.setRuleName(request.ruleName());
        rule.setTimeDimension(request.timeDimension());
        rule.setCategory(request.category());
        rule.setSubCategory(request.subCategory());
        rule.setRegion(request.region());
        rule.setCountThreshold(request.countThreshold());
        rule.setWarningLevel(request.warningLevel());
        // 旧格式兼容：显式传入时仍校验并落库；新前端只传双限制字段
        validatePushTargets(request.pushTargets());
        rule.setPushTargets(request.pushTargets() == null || request.pushTargets().isEmpty()
                ? null : String.join(",", request.pushTargets()));
        // 双限制：辖区限制 + 部门类别限制（空=不限制）
        validatePushCounties(request.pushCounties());
        validatePushCategories(request.pushCategories());
        rule.setPushCounties(joinList(request.pushCounties()));
        rule.setPushCategories(joinList(request.pushCategories()));
        return rule;
    }

    /** 逗号拼接列表（空列表 → null，语义为不限制） */
    private String joinList(List<String> list) {
        // 列表转逗号串
        if (list == null || list.isEmpty()) {
            return null;
        }
        return String.join(",", list);
    }

    /** 辖区限制校验：每项必须是 sys_region 中启用的区县 code */
    private void validatePushCounties(List<String> counties) {
        // 校验辖区限制
        if (counties == null || counties.isEmpty()) {
            return;
        }
        for (String c : counties) {
            String v = c == null ? "" : c.trim();
            if (v.isEmpty()) {
                continue;
            }
            SysRegion county = regionService.getByCode(v);
            if (county == null || !"COUNTY".equals(county.getLevel())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "辖区限制不合法：" + v + "（须为区县编码）");
            }
        }
    }

    /** 部门类别限制校验：每项必须是 8 大分派部门分类 code */
    private void validatePushCategories(List<String> categories) {
        // 校验部门类别限制
        if (categories == null || categories.isEmpty()) {
            return;
        }
        for (String c : categories) {
            String v = c == null ? "" : c.trim();
            if (!v.isEmpty() && !DEPT_PUSH_CODES.contains(v)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "部门类别限制不合法：" + v + "（须为8大分类code）");
            }
        }
    }

    /** 推送对象条目校验（56部门口径）：ADMIN=管理端；8大分类code=全市该分类；区县code:分类code=指定区县部门 */
    private void validatePushTargets(List<String> targets) {
        // 校验推送对象
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (String t : targets) {
            String v = t == null ? "" : t.trim();
            if (v.isEmpty() || "ADMIN".equals(v) || DEPT_PUSH_CODES.contains(v)) {
                continue;
            }
            int idx = v.indexOf(':');
            if (idx > 0 && idx < v.length() - 1) {
                String countyCode = v.substring(0, idx);
                String dept = v.substring(idx + 1);
                SysRegion county = regionService.getByCode(countyCode);
                if (county != null && "COUNTY".equals(county.getLevel()) && DEPT_PUSH_CODES.contains(dept)) {
                    continue;
                }
            }
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "推送对象不合法：" + v + "（支持 ADMIN/分类code/区县code:分类code）");
        }
    }

    private List<String> splitTargets(String targets) {
        // 拆分目标字符串
        List<String> list = new ArrayList<>();
        if (targets != null && !targets.isEmpty()) {
            for (String t : targets.split(",")) {
                if (!t.isBlank()) {
                    list.add(t.trim());
                }
            }
        }
        return list;
    }

    /**
     * 归属部门展开（部门端 FIND_IN_SET 数据隔离口径）：
     * 旧格式（push_targets 非空）：按原口径解析（纯分类code=全市；区县:分类=指定区县；剔除 ADMIN）。
     * 新格式（push_targets 为空，双限制）：辖区限制（空=全部区县）× 部门类别限制（空=全部8类）笛卡尔积展开为
     * 「区县code:分类code」复合键串；管理端不在 department 中体现（管理端始终可见全部记录）。
     */
    private String deriveDepartment(WarningRule rule) {
        // 派生归属部门
        if (rule == null) {
            return null;
        }
        List<String> legacy = splitTargets(rule.getPushTargets());
        if (!legacy.isEmpty()) {
            List<String> depts = new ArrayList<>();
            for (String t : legacy) {
                if (!t.isEmpty() && !"ADMIN".equals(t)) {
                    depts.add(t);
                }
            }
            return depts.isEmpty() ? null : String.join(",", depts);
        }
        // 新双限制语义：空 = 不限制 = 全部区县 / 全部部门类别
        List<String> counties = splitTargets(rule.getPushCounties());
        if (counties.isEmpty()) {
            counties = regionService.listCounties().stream()
                    .map(SysRegion::getRegionCode).collect(Collectors.toList());
        }
        List<String> categories = splitTargets(rule.getPushCategories());
        if (categories.isEmpty()) {
            categories = new ArrayList<>(DEPT_PUSH_CODES);
        }
        List<String> depts = new ArrayList<>();
        for (String county : counties) {
            for (String dept : categories) {
                depts.add(county + ":" + dept);
            }
        }
        return depts.isEmpty() ? null : String.join(",", depts);
    }

    /** 归一化区县名：从规则/预判辖区（可能含街道级前缀，如「海港区新城区」）提取区县级名称；查不到返回 null（视为全辖区） */
    private String resolveCountyName(String region) {
        // 解析区县名
        if (region == null || region.isBlank()) {
            return null;
        }
        for (SysRegion county : regionService.listCounties()) {
            if (region.startsWith(county.getRegionName())) {
                return county.getRegionName();
            }
        }
        return null;
    }

    /** 预判周期转预警规则时间维度：DAY/3DAY/7DAY 或 DAY/3_DAY/7_DAY 统一转 3_DAY 口径 */
    private String toTimeDimension(String period) {
        // 转换时间维度
        if (period == null) {
            return "7_DAY";
        }
        return switch (period) {
            case "DAY" -> "DAY";
            case "3DAY", "3_DAY" -> "3_DAY";
            default -> "7_DAY";
        };
    }

    /** 一级分类 code → 中文名（classify_primary），查不到时回退原码 */
    private String primaryName(String code) {
        ClassifyPrimary p = classifyPrimaryMapper.selectByCode(code);
        return p == null || p.getName() == null || p.getName().isBlank() ? code : p.getName();
    }

    /** 预判草稿规则命名：区县·二级(或一级)·未来N天高发预警（方案 7 约定格式） */
    private String buildPredictionRuleName(String region, String nameTarget, String period) {
        // 构建预判规则名
        StringBuilder name = new StringBuilder();
        if (!region.isEmpty()) {
            name.append(region).append("·");
        }
        name.append(nameTarget).append("·未来").append(periodDaysLabel(period)).append("天高发预警");
        return name.toString();
    }

    /** 预判周期转天数文案：DAY/3DAY/7DAY 或 DAY/3_DAY/7_DAY -> 1/3/7 */
    private String periodDaysLabel(String period) {
        // 周期天数标签
        if (period == null) {
            return "7";
        }
        return switch (period) {
            case "DAY" -> "1";
            case "3DAY", "3_DAY" -> "3";
            default -> "7";
        };
    }

    /** 预判热点预警等级：按预测量 + 置信度分级（方案 7 约定，无强依赖，纯本地换算） */
    private String levelFromPrediction(Integer predictedCount, Double confidence) {
        // 分级
        int cnt = predictedCount == null ? 0 : predictedCount;
        double conf = confidence == null ? 0.0 : confidence;
        if (cnt >= 100 || (cnt >= 50 && conf >= 0.8)) {
            return "URGENT";
        }
        if (cnt >= 30 || conf >= 0.7) {
            return "SEVERE";
        }
        return "GENERAL";
    }

    /** null/空白归一为空串 */
    private String blank(String s) {
        // 空串归一
        return s == null ? "" : s.trim();
    }


    public PageData<Map<String, Object>> pageRecords(String warningLevel, String category, String region,
                                                     String status, LocalDateTime startTime, LocalDateTime endTime,
                                                     int page, int pageSize) {
        // 分页查询记录
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> rows =
                recordMapper.selectPage(warningLevel, category, region, status, startTime, endTime, offset, pageSize);
        long total = recordMapper.countPage(warningLevel, category, region, status, startTime, endTime);
        List<Map<String, Object>> list = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("warning_no", v(r, "warningNo", "warning_no"));
            m.put("rule_id", v(r, "ruleId", "rule_id"));
            m.put("rule_name", v(r, "ruleName", "rule_name"));
            m.put("warning_level", v(r, "warningLevel", "warning_level"));
            m.put("category", r.get("category"));
            m.put("sub_category", v(r, "subCategory", "sub_category"));
            m.put("region", r.get("region"));
            m.put("county_name", v(r, "countyName", "county_name"));
            m.put("department", r.get("department"));
            m.put("trigger_time", v(r, "triggerTime", "trigger_time"));
            m.put("status", r.get("status"));
            m.put("review_time", v(r, "reviewTime", "review_time"));
            m.put("related_attention", v(r, "relatedAttention", "related_attention"));
            m.put("ai_summary", v(r, "aiSummary", "ai_summary"));
            m.put("measure", r.get("measure"));
            m.put("ref_cases", v(r, "refCases", "ref_cases"));
            m.put("respond_status", v(r, "respondStatus", "respond_status"));
            m.put("respond_time", v(r, "respondTime", "respond_time"));
            return m;
        }).toList();
        return PageData.of(list, total, page, pageSize);
    }


    /**
     * 每日定时预警扫描（默认 01:30，预判 01:17 之后执行），cron 可在 application.yaml 调整
     */
    @Scheduled(cron = "${app.warning.cron:0 30 1 * * ?}")
    public void scheduledWarning() {
        // 定时执行预警扫描
        runWarningScan();
    }

    /**
     * 预警扫描：遍历启用规则，时间维度窗口内工单数达到阈值即触发预警
     * AI 总结/应对措施生成失败时降级为空，不阻断预警入库
     */
    public int runWarningScan() {
        // 执行预警扫描
        List<WarningRule> rules = ruleMapper.selectEnabled();
        int triggered = 0;
        for (WarningRule rule : rules) {
            try {
                if (scanRule(rule)) {
                    triggered++;
                }
            } catch (Exception e) {
                log.warn("预警规则扫描失败 ruleId={}：{}", rule.getId(), e.getMessage());
            }
        }
        log.info("预警扫描完成：启用规则{}条，触发{}条", rules.size(), triggered);
        return triggered;
    }

    private boolean scanRule(WarningRule rule) {
        // 扫描规则匹配：窗口内匹配规则(一级+二级分类/辖区)的工单累计关注度(attention_count 求和)达到阈值即触发
        int days = DIMENSION_DAYS.getOrDefault(rule.getTimeDimension(), 1);
        LocalDateTime start = LocalDateTime.now().minusDays(days);
        long sumAttention = adminWorkOrderMapper.sumAttentionForWarning(start, rule.getCategory(),
                rule.getSubCategory(), rule.getRegion());
        if (sumAttention < rule.getCountThreshold()) {
            return false;
        }
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        if (recordMapper.countByRuleToday(rule.getId(), dayStart) > 0) {
            log.info("规则 {} 当日已触发，跳过", rule.getRuleName());
            return false;
        }
        WarningRecord record = new WarningRecord();
        record.setWarningNo(nextWarningNo());
        record.setRuleId(rule.getId());
        record.setWarningLevel(rule.getWarningLevel());
        record.setCategory(rule.getCategory());
        record.setRegion(rule.getRegion());
        record.setCountyName(resolveCountyName(rule.getRegion()));
        record.setDepartment(deriveDepartment(rule));
        record.setStatus("PENDING");
        record.setTriggerTime(LocalDateTime.now());
        record.setSubCategory(rule.getSubCategory());
        record.setRelatedAttention((int) Math.min(sumAttention, Integer.MAX_VALUE));
        record.setRespondStatus("UNRESPONDED");
        recordMapper.insert(record);
        fillAiContent(record, rule, start);
        auditLogService.recordOperation("SYSTEM", "warning-scan",
                "预警触发：" + record.getWarningNo() + "（" + rule.getRuleName()
                        + "，累计关注度" + sumAttention + "，等级" + LEVEL_LABELS.getOrDefault(rule.getWarningLevel(), rule.getWarningLevel()) + "）");
        log.warn("预警触发 {}：{}/{} 窗口内累计关注度 {}（阈值 {}）", record.getWarningNo(),
                rule.getCategory() == null ? "全分类" : rule.getCategory(),
                (rule.getSubCategory() != null && !rule.getSubCategory().isBlank() ? rule.getSubCategory() : "") + "/"
                        + (rule.getRegion() == null ? "全辖区" : rule.getRegion()),
                sumAttention, rule.getCountThreshold());
        return true;
    }

    /**
     * 预判成果联动预警（需求 9(5)）：预判结果命中预警规则阈值即触发预警
     * 复用现有落库/推送/审计逻辑，仅数据来源为预测量（非实际工单数）
     */
    public int scanByPrediction(List<PredictionResult> predictions) {
        // 执行scanByPrediction操作
        if (predictions == null || predictions.isEmpty()) {
            return 0;
        }
        List<WarningRule> rules = ruleMapper.selectEnabled();
        int triggered = 0;
        for (WarningRule rule : rules) {
            // 预测量按一级分类/辖区聚合，无二级维度：设置了二级分类的规则不做预判联动，避免误触发
            if (rule.getSubCategory() != null && !rule.getSubCategory().isBlank()) {
                log.info("规则[{}]设置了二级分类，预判联动跳过（预测量无二级维度）", rule.getRuleName());
                continue;
            }
            for (PredictionResult p : predictions) {
                if (!matchesPrediction(rule, p)) {
                    continue;
                }
                if (p.getPredictedCount() == null || p.getPredictedCount() < rule.getCountThreshold()) {
                    continue;
                }
                if (triggerByPrediction(rule, p)) {
                    triggered++;
                    break;
                }
            }
        }
        log.info("预判预警联动完成：启用规则{}条，触发{}条", rules.size(), triggered);
        return triggered;
    }

    private boolean matchesPrediction(WarningRule rule, PredictionResult p) {
        // 匹配预判结果
        if (rule.getCategory() != null && !rule.getCategory().isBlank()
                && !rule.getCategory().equals(p.getCategory())) {
            return false;
        }
        if (rule.getRegion() != null && !rule.getRegion().isBlank()) {
            if (p.getRegion() == null || !p.getRegion().startsWith(rule.getRegion())) {
                return false;
            }
        }
        return true;
    }

    private boolean triggerByPrediction(WarningRule rule, PredictionResult p) {
        // 依据预判结果触发预警
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        if (recordMapper.countByRuleToday(rule.getId(), dayStart) > 0) {
            return false;
        }
        WarningRecord record = new WarningRecord();
        record.setWarningNo(nextWarningNo());
        record.setRuleId(rule.getId());
        record.setWarningLevel(rule.getWarningLevel());
        record.setCategory(p.getCategory());
        record.setSubCategory(rule.getSubCategory());
        record.setRegion(p.getRegion());
        record.setCountyName(resolveCountyName(p.getRegion()));
        record.setDepartment(deriveDepartment(rule));
        record.setStatus("PENDING");
        record.setTriggerTime(LocalDateTime.now());
        record.setRelatedAttention(p.getPredictedCount());
        record.setRespondStatus("UNRESPONDED");
        record.setAiSummary("预判模型预警：预计" + p.getPredictedCount() + "条工单（置信度"
                + percent(p.getConfidence() == null ? null : p.getConfidence().doubleValue()) + "%）");
        recordMapper.insert(record);
        fillPredictionMeasure(record);
        auditLogService.recordOperation("SYSTEM", "warning-scan",
                "预判预警触发：" + record.getWarningNo() + "（" + rule.getRuleName() + "）");
        log.warn("预判预警触发 {}：分类{} 辖区{} 预测量{}", record.getWarningNo(),
                p.getCategory(), p.getRegion(), p.getPredictedCount());
        return true;
    }

    /**
     * 应对措施富文本上下文（需求11）：预警信息 + 窗口内命中工单明细 + 同类概览 + 预警总结。
     * 相比仅传入一段总结，拼入真实辖区/分类/工单明细，使大模型给出的措施更有针对性。
     */
    private String buildWarningScene(WarningRecord record, WarningRule rule, LocalDateTime start, String summaryText) {
        // 构建措施富文本上下文
        List<Map<String, Object>> details = adminWorkOrderMapper.selectWarningContext(
                start, rule.getCategory(), rule.getSubCategory(), rule.getRegion());
        StringBuilder sb = new StringBuilder();
        sb.append("【预警信息】\n");
        sb.append("规则：").append(rule.getRuleName())
                .append("｜等级：").append(LEVEL_LABELS.getOrDefault(rule.getWarningLevel(), rule.getWarningLevel()))
                .append("｜时间维度：近").append(DIMENSION_DAYS.getOrDefault(rule.getTimeDimension(), 1)).append("天\n");
        sb.append("分类：").append(rule.getCategory() == null ? "全部" : rule.getCategory())
                .append(rule.getSubCategory() != null && !rule.getSubCategory().isBlank() ? "/" + rule.getSubCategory() : "").append("\n");
        sb.append("辖区：").append(rule.getRegion() == null ? "全部" : rule.getRegion()).append("\n\n");

        sb.append("【窗口内命中工单明细】\n");
        if (details.isEmpty()) {
            sb.append("暂无明细\n");
        } else {
            for (int i = 0; i < details.size(); i++) {
                Map<String, Object> d = details.get(i);
                sb.append(i + 1).append(". [").append(str(d.get("order_no"))).append("] ")
                        .append(str(d.get("title"))).append("\n");
                sb.append("   分类：").append(str(d.get("category"))).append("/").append(str(d.get("sub_category")))
                        .append("｜辖区：").append(str(d.get("region")))
                        .append("｜关注度：").append(str(d.get("attention_count")))
                        .append("｜状态：").append(str(d.get("status"))).append("\n");
                sb.append("   内容：").append(truncate(str(d.get("content")), 200)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("【近期同类概览】\n窗口内此类工单共 ").append(details.size()).append(" 件（明细最多展示20条）\n\n");
        sb.append("【预警总结】\n").append(summaryText == null ? "" : summaryText);
        return sb.toString();
    }

    /** AI 总结与应对措施生成并回填（AI 服务不可用降级：ai_summary/measure 保持为空） */
    private void fillAiContent(WarningRecord record, WarningRule rule, LocalDateTime start) {
        // 填充AI生成内容
        List<Map<String, Object>> texts = adminWorkOrderMapper.selectWarningTexts(
                start, rule.getCategory(), rule.getSubCategory(), rule.getRegion());
        List<String> orderTexts = texts.stream()
                .map(t -> String.valueOf(t.get("title")) + "。" + String.valueOf(t.get("content")))
                .toList();
        Optional<AiWarningSummaryResult> summary =
                aiServiceClient.warningSummary(record.getId(), orderTexts);
        if (summary.isEmpty() || summary.get().summary() == null || summary.get().summary().isBlank()) {
            return;
        }
        String summaryText = summary.get().summary();
        Double confidence = summary.get().confidence();
        record.setAiSummary("AI生成·置信度" + percent(confidence) + "%：" + summaryText);

        // 优秀处置案例检索（RAG）：以预警总结为查询召回相似案例，拼入场景供措施生成借鉴，并保存溯源编号
        String scene = buildWarningScene(record, rule, start, summaryText);
        scene = appendCaseReferences(scene, rule, summaryText, record);

        aiServiceClient.warningMeasure(scene)
                .map(AiWarningMeasureResult::measures)
                .ifPresent(measures -> {
                    if (!measures.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < measures.size(); i++) {
                            sb.append(i + 1).append(". ").append(measures.get(i).measure()).append("\n");
                        }
                        record.setMeasure(sb.toString().trim());
                    }
                });
        recordMapper.updateAiContent(record);
        auditLogService.recordAiGeneration("WARNING",
                "AI生成·预警总结与应对措施：" + record.getWarningNo(),
                confidence,
                "预警规则[" + rule.getRuleName() + "]累计关注度" + record.getRelatedAttention() + "，措施为富文本上下文+优秀处置案例RAG生成",
                "qwen-plus");
    }

    /**
     * 优秀处置案例 RAG 溯源（需求 11 预警建议强化）：调 AI 服务检索相似处置案例，
     * 拼入措施生成上下文，并把命中的案例编号回填到预警记录 ref_cases 供前端溯源展示。
     * 检索失败（AI 服务不可用/无命中）降级为原场景，不阻断措施生成。
     */
    private String appendCaseReferences(String scene, WarningRule rule, String query, WarningRecord record) {
        // 拼接案例RAG检索来源
        try {
            List<AiCaseSearchItem> cases = aiServiceClient.searchCases(query, rule.getCategory(), rule.getRegion(), 5)
                    .map(AiCaseSearchResult::cases)
                    .orElse(List.of());
            if (cases == null || cases.isEmpty()) {
                return scene;
            }
            StringBuilder sb = new StringBuilder(scene);
            sb.append("\n\n【参考优秀处置案例】\n");
            List<String> refCaseNos = new ArrayList<>();
            for (int i = 0; i < cases.size(); i++) {
                AiCaseSearchItem c = cases.get(i);
                sb.append(i + 1).append(". [").append(str(c.caseNo())).append("] ").append(str(c.title()))
                        .append("（相似度").append(c.similarity() == null ? 0 : Math.round(c.similarity() * 100)).append("%）\n");
                sb.append("   处置方案：").append(str(c.measurePlan())).append("\n");
                if (c.measureResult() != null && !c.measureResult().isBlank()) {
                    sb.append("   处置效果：").append(c.measureResult()).append("\n");
                }
                refCaseNos.add(c.caseNo());
            }
            record.setRefCases(String.join(",", refCaseNos));
            return sb.toString();
        } catch (Exception e) {
            log.warn("处置案例检索失败（案例 RAG 降级）：{}", e.getMessage());
            return scene;
        }
    }

    /** 预判联动补充应对措施（A4）：基于预判描述调 AI 生成措施并回填，AI 不可用降级为空 */
    private void fillPredictionMeasure(WarningRecord record) {
        // 填充预判Measure
        if (record.getAiSummary() == null || record.getAiSummary().isBlank()) {
            return;
        }
        aiServiceClient.warningMeasure(record.getAiSummary())
                .map(AiWarningMeasureResult::measures)
                .ifPresent(measures -> {
                    if (!measures.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < measures.size(); i++) {
                            sb.append(i + 1).append(". ").append(measures.get(i).measure()).append("\n");
                        }
                        record.setMeasure(sb.toString().trim());
                        recordMapper.updateAiContent(record);
                    }
                });
    }

    /**
     * 站内消息推送（需求 11(3)）：管理端始终提醒 + 部门侧按规则推送限制精准投递。
     * 部门目标取 record.department（已按辖区限制 × 部门类别限制展开的「区县code:分类code」复合键串；
     * 旧格式纯分类code=全市该分类）：复合键按 user_type=分类 且 region_code=区县 过滤，
     * 纯 code 推该分类全部启用账号（兼容过渡期无区县账号）。
     */
    private void pushMessages(WarningRule rule, WarningRecord record) {
        // 批量推送消息
        String levelLabel = LEVEL_LABELS.getOrDefault(rule.getWarningLevel(), rule.getWarningLevel());
        String title = "【" + levelLabel + "预警】" + rule.getRuleName();
        StringBuilder content = new StringBuilder();
        content.append("触发时间：").append(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(record.getTriggerTime()));
        if (rule.getCategory() != null) {
            content.append("；涉及分类：").append(rule.getCategory());
        }
        if (rule.getSubCategory() != null && !rule.getSubCategory().isBlank()) {
            content.append("-").append(rule.getSubCategory());
        }
        if (rule.getRegion() != null) {
            content.append("；涉及辖区：").append(rule.getRegion());
        }
        content.append("；累计关注度：").append(record.getRelatedAttention()).append("。请及时关注处置");
        boolean urgent = "URGENT".equals(rule.getWarningLevel());
        int pushed = 0;
        Set<Long> notified = new HashSet<>();
        // 管理端始终收到站内提醒（不受推送限制约束）
        for (SysUser user : userMapper.selectEnabledByUserType("ADMIN")) {
            pushed += insertWarningMessage(user, title, content.toString(), urgent, rule);
            notified.add(user.getId());
        }
        // 部门侧：按归属部门复合键精准投递（只在相应辖区的相应部门账号范围）
        for (String entry : splitTargets(record.getDepartment())) {
            int idx = entry.indexOf(':');
            String deptCode = idx > 0 ? entry.substring(idx + 1) : entry;
            String countyCode = idx > 0 ? entry.substring(0, idx) : null;
            for (SysUser user : userMapper.selectEnabledByUserType(deptCode)) {
                if (!notified.add(user.getId())) {
                    continue;
                }
                if (countyCode != null && !countyCode.equals(user.getRegionCode())) {
                    continue;
                }
                pushed += insertWarningMessage(user, title, content.toString(), urgent, rule);
            }
        }
        log.info("预警 {} 站内消息推送 {} 条（部门目标 {}）", record.getWarningNo(), pushed,
                splitTargets(record.getDepartment()));
    }

    /** 单账号站内消息落库：返回 1；紧急预警另下发短信（开发模式 log-sender 仅打印日志） */
    private int insertWarningMessage(SysUser user, String title, String content, boolean urgent, WarningRule rule) {
        // 插入站内消息
        SysMessage message = new SysMessage();
        message.setUserId(user.getId());
        message.setMsgType(MSG_TYPE_WARNING);
        message.setTitle(title);
        message.setContent(content);
        message.setIsRead(0);
        message.setChannel("IN_APP");
        messageMapper.insert(message);
        if (urgent && user.getPhone() != null && !user.getPhone().isBlank()) {
            try {
                smsSender.send(user.getPhone(), "【紧急预警】" + rule.getRuleName());
            } catch (Exception smsEx) {
                log.warn("紧急预警短信下发失败 userId={}: {}", user.getId(), smsEx.getMessage());
            }
        }
        return 1;
    }

    /** 预警编号：WR + 日期 + 当日序号（schema.sql warning_record.warning_no） */
    private String nextWarningNo() {
        // 生成下一个预警编号
        String prefix = "WR" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int seq = recordMapper.countByPrefix(prefix) + 1;
        return prefix + String.format("%03d", seq);
    }

    private int percent(Double confidence) {
        // 计算百分比
        if (confidence == null) {
            return 0;
        }
        return (int) Math.round(confidence * 100);
    }

    /** 空安全转字符串：null 返回空串，避免富文本出现 "null" */
    private String str(Object o) {
        // 处理字符串为空的情况
        return o == null ? "" : String.valueOf(o);
    }

    /** 内容截断：超过 max 字符则截断并加省略号 */
    private String truncate(String s, int max) {
        // 截断字符串
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "…";
    }

    /**
     * MyBatis map 结果键名双兼容读取：优先 camelCase，回退 snake_case
     */
    private Object v(Map<String, Object> m, String camel, String snake) {
        // 处理WarningService的v操作
        if (m.containsKey(camel)) {
            return m.get(camel);
        }
        return m.get(snake);
    }
}