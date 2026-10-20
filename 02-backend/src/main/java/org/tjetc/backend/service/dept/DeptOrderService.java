package org.tjetc.backend.service.dept;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.entity.common.WorkOrder;
import org.tjetc.backend.mapper.dept.DeptWorkOrderMapper;
import org.tjetc.backend.mapper.staff.OrderDelayApplyMapper;
import org.tjetc.backend.mapper.staff.OrderDisposeAttachmentMapper;
import org.tjetc.backend.mapper.common.WorkOrderAppealMapper;
import org.tjetc.backend.mapper.citizen.AppealChildMapper;
import org.tjetc.backend.mapper.citizen.OrderEvaluateMapper;
import org.tjetc.backend.mapper.common.SysFileMapper;
import org.tjetc.backend.entity.citizen.OrderEvaluate;
import org.tjetc.backend.service.admin.FileService;
import org.tjetc.backend.service.admin.OrderLogService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门端工单查询与详情（需求 15 权限内工单查询 / API 9.5/9.12）——三端数据解耦：
 * 只读部门端副本表 dept_work_order（工单未分派至部门即副本不存在，天然不可见），
 * 按 JWT 中 user_type（=工单 category）自动过滤数据范围，前端不可越权（API 2.5）。
 * 时间线只读部门端日志表 dept_order_log。
 */
@Service
@RequiredArgsConstructor
public class DeptOrderService {

    private final DeptWorkOrderMapper deptWorkOrderMapper;
    private final OrderLogService orderLogService;
    private final OrderDisposeAttachmentMapper disposeAttachmentMapper;
    private final OrderDelayApplyMapper delayApplyMapper;
    private final WorkOrderAppealMapper workOrderAppealMapper;
    private final AppealChildMapper appealChildMapper;
    private final OrderEvaluateMapper evaluateMapper;
    private final SysFileMapper sysFileMapper;
    private final FileService fileService;

    /**
     * 权限内工单查询（API 9.5）：本分类（user_type）工单，可选按街道前缀进一步过滤；
     * statuses：多状态集合筛选（工作台「待审核」卡 = 待市民评价/已评价/街道办已处理）
     */
    public PageData<Map<String, Object>> page(String orderNo, String keyword, String status, List<String> statuses,
                                              String streetOfficeFilter,
                                              LocalDateTime startTime, LocalDateTime endTime,
                                              int page, int pageSize, LoginUser user) {
        String category = user.userType();
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> rows = deptWorkOrderMapper.selectPageDept(
                orderNo, keyword, status, statuses, startTime, endTime, category, streetOfficeFilter,
                user.regionCode(), offset, pageSize);
        long total = deptWorkOrderMapper.countPageDept(
                orderNo, keyword, status, statuses, startTime, endTime, category, streetOfficeFilter, user.regionCode());
        // 显式转换 snake_case 键名（与 API 文档风格一致）
        List<Map<String, Object>> list = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("order_no", v(r, "orderNo", "order_no"));
            m.put("title", r.get("title"));
            m.put("content", r.get("content"));
            m.put("category", r.get("category"));
            m.put("sub_category", v(r, "subCategory", "sub_category"));
            m.put("level", r.get("level"));
            m.put("region", r.get("region"));
            m.put("department", r.get("department"));
            m.put("street_office", v(r, "streetOffice", "street_office"));
            m.put("status", r.get("status"));
            m.put("is_overdue", v(r, "isOverdue", "is_overdue"));
            m.put("deadline", r.get("deadline"));
            m.put("create_time", v(r, "createTime", "create_time"));
            m.put("dispose_tag", v(r, "disposeTag", "dispose_tag"));
            m.put("dept_audit_status", v(r, "deptAuditStatus", "dept_audit_status"));
            m.put("final_audit_status", v(r, "finalAuditStatus", "final_audit_status"));
            m.put("hit_keywords", v(r, "hitKeywords", "hit_keywords"));
            m.put("confidence", r.get("confidence"));
            m.put("dept_receive_time", v(r, "deptReceiveTime", "dept_receive_time"));
            m.put("street_receive_time", v(r, "streetReceiveTime", "street_receive_time"));
            return m;
        }).toList();
        return PageData.of(list, total, page, pageSize);
    }

    /**
     * 权限内工单详情（API 9.12）：含命中关键词（高亮展示用）、处置附件与部门端时间线
     */
    public Map<String, Object> detail(Long id, LoginUser user) {
        WorkOrder order = requireScope(id, user);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", order.getId());
        data.put("order_no", order.getOrderNo());
        data.put("title", order.getTitle());
        data.put("content", order.getContent());
        data.put("category", order.getCategory());
        data.put("sub_category", order.getSubCategory());
        data.put("level", order.getLevel());
        data.put("region", order.getRegion());
        data.put("department", order.getDepartment());
        data.put("street_office", order.getStreetOffice());
        data.put("status", order.getStatus());
        data.put("is_overdue", order.getIsOverdue());
        data.put("deadline", order.getDeadline());
        data.put("dispose_plan", order.getDisposePlan());
        data.put("dispose_result", order.getDisposeResult());
        data.put("dispose_tag", order.getDisposeTag());
        data.put("dispose_time", order.getDisposeTime());
        data.put("dept_audit_remark", order.getDeptAuditRemark());
        data.put("dept_return_count", order.getDeptReturnCount());
        data.put("delay_count", order.getDelayCount());
        data.put("hit_keywords", order.getHitKeywords() == null ? List.of()
                : List.of(order.getHitKeywords().split(",")));
        data.put("attachments", disposeAttachmentMapper.selectByOrderId(id).stream()
                .map(a -> Map.of("id", a.getId(), "file_name", a.getFileName(),
                        "file_url", fileService.presignedUrl(a.getFileUrl()),
                        "attachment_type", a.getAttachmentType()))
                .toList());
        // 归并成该工单的子诉求上的市民信息（去重；不暴露组成子诉求内容）
        List<Map<String, Object>> citizens = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Long appealId : workOrderAppealMapper.selectAppealIdsByOrderId(id)) {
            org.tjetc.backend.entity.citizen.AppealChild c = appealChildMapper.selectById(appealId);
            if (c == null) continue;
            String key = (c.getCitizenName() == null ? "" : c.getCitizenName()) + "|" + c.getCitizenPhone();
            if (seen.contains(key)) continue;
            seen.add(key);
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("appeal_no", c.getAppealNo());
            cm.put("citizen_name", org.tjetc.backend.util.MaskUtil.maskName(c.getCitizenName()));
            cm.put("citizen_phone", org.tjetc.backend.util.MaskUtil.maskPhone(c.getCitizenPhone()));
            citizens.add(cm);
        }
        data.put("citizens", citizens);
        // 诉求附件（市民提交时上传，来源子诉求所属父诉求；MinIO 预签名下载链接）
        List<Map<String, Object>> appealFiles = new java.util.ArrayList<>();
        java.util.Set<Long> appealIds = new java.util.HashSet<>();
        for (Long appealId : workOrderAppealMapper.selectAppealIdsByOrderId(id)) {
            org.tjetc.backend.entity.citizen.AppealChild c = appealChildMapper.selectById(appealId);
            if (c == null || c.getParentId() == null || !appealIds.add(c.getParentId())) continue;
            for (org.tjetc.backend.entity.common.SysFile f : sysFileMapper.selectByAppealId(c.getParentId())) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("file_id", f.getId());
                fm.put("file_name", f.getFileName());
                fm.put("url", fileService.presignedUrl(f.getFileUrl()));
                appealFiles.add(fm);
            }
        }
        data.put("appeal_attachments", appealFiles);
        // 三端数据解耦：部门端时间线只读 dept_order_log
        data.put("timeline", orderLogService.timeline(id, OrderLogService.TimelineEnd.DEPT));
        // 市民评价（详情弹窗下方展示；未评价为 null）——处置完成后市民可评，部门收评价或超期才可审核
        OrderEvaluate evaluate = evaluateMapper.selectByOrderId(id);
        if (evaluate != null) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("score", evaluate.getScore());
            ev.put("label", evaluate.getLabel());
            ev.put("remark", evaluate.getRemark());
            ev.put("evaluate_time", evaluate.getEvaluateTime());
            data.put("evaluation", ev);
        } else {
            data.put("evaluation", null);
        }
        return data;
    }

    /** 取部门端副本并校验数据范围：越权访问返回 40203（供附件上传/时间线等接口复用，防横向越权） */
    public WorkOrder requireScope(Long id, LoginUser user) {
        WorkOrder order = deptWorkOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "工单不存在或未分派至本部门");
        }
        // 部门账号：仅限本分类（user_type）+ 本区县（region_code）的工单
        if (order.getCategory() == null || !order.getCategory().equals(user.userType())) {
            throw new BusinessException(ResultCode.REGION_FORBIDDEN, "越权访问其他部门工单数据");
        }
        if (user.regionCode() != null && !user.regionCode().isBlank()
                && !user.regionCode().equals(order.getCountyCode())) {
            throw new BusinessException(ResultCode.REGION_FORBIDDEN, "越权访问其他区县部门工单数据");
        }
        return order;
    }

    /**
     * 待审核工单列表（API 11.2.1）：STREET_DONE 状态，本分类过滤 + 街道办/处置标签/超时筛选
     */
    public PageData<Map<String, Object>> auditingPage(String streetOffice, String disposeTag,
                                                      Boolean isOverdue, LocalDateTime startTime,
                                                      LocalDateTime endTime, int page, int pageSize,
                                                      LoginUser user) {
        String dept = user.userType();
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> rows = deptWorkOrderMapper.selectAuditingList(
                streetOffice, disposeTag, isOverdue, startTime, endTime, dept, user.regionCode(), offset, pageSize);
        long total = deptWorkOrderMapper.countAuditingList(
                streetOffice, disposeTag, isOverdue, startTime, endTime, dept, user.regionCode());
        List<Map<String, Object>> list = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("order_no", v(r, "orderNo", "order_no"));
            m.put("summary", r.get("summary"));
            m.put("street_office", v(r, "streetOffice", "street_office"));
            m.put("dispose_tag", v(r, "disposeTag", "dispose_tag"));
            m.put("submit_time", v(r, "disposeTime", "dispose_time"));
            m.put("is_overdue", v(r, "isOverdue", "is_overdue"));
            m.put("level", r.get("level"));
            m.put("category", r.get("category"));
            return m;
        }).toList();
        return PageData.of(list, total, page, pageSize);
    }

    /** 待审批延期申请列表（API 11.2.6，文档未定义查询接口，按需求 22(5) 补充；按分类+区县隔离） */
    public List<Map<String, Object>> delayPendingList(LoginUser user) {
        return delayApplyMapper.selectPendingByDept(user.userType(), user.regionCode());
    }

    /**
     * 部门工作台首页统计（仅本部门工单，读部门端副本表）。
     * 返回 order_total / pending_handle / pending_verify / pending_assign /
     * pending_audit / month_done / avg_score / pending_delay
     */
    public Map<String, Object> workbenchSummary(LoginUser user) {
        String category = user.userType();
        LocalDate now = LocalDate.now();
        Map<String, Object> m = new LinkedHashMap<>(
                deptWorkOrderMapper.workbenchSummary(category, user.regionCode(), now.getYear(), now.getMonthValue()));
        m.put("order_total", num(m, "order_total"));
        m.put("pending_handle", num(m, "pending_handle"));
        m.put("pending_verify", num(m, "pending_verify"));
        m.put("pending_assign", num(m, "pending_assign"));
        m.put("pending_audit", num(m, "pending_audit"));
        m.put("month_done", num(m, "month_done"));
        // 待审批延期仅部门端有意义（按分类+区县隔离）
        m.put("pending_delay", delayApplyMapper.countPendingByDept(category, user.regionCode()));
        // 部门平均评分保留 1 位小数
        double score = ((Number) m.get("avg_score")).doubleValue();
        m.put("avg_score", Math.round(score * 100L) / 100.0);
        return m;
    }

    /** 归一化 work_order 聚合计数：SQL 空结果可能返回 null */
    private long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }

    /**
     * 本部门每日诉求趋势（工作台「本部门诉求趋势」折线图）：
     * 一级分类锁定为登录部门 user_type，仅开放二级分类（空 = 一级总括）、区县（前缀匹配只到区级）与时间窗口
     *
     * @param days        近 N 天（1~90，超界自动收敛）
     * @param subCategory 二级分类名（空 = 本部门一级总括）
     * @param region      区县名（空 = 不过滤；按前缀匹配只筛到区级）
     * @param user        登录用户（user_type 即一级分类 code）
     * @return { dates: [MM-DD...]（升序，截止今天）, values: [每日数量...] }
     */
    public Map<String, Object> dailyTrend(Integer days, String subCategory, String region, LoginUser user) {
        // 天数收敛，防止恶意大窗口拖垮查询
        int safeDays = Math.min(Math.max(days == null ? 15 : days, 1), 90);

        // 查询副本表中实际有数据的日期计数（category 锁定本部门，区县锁定本账号，前端不可越权）
        List<Map<String, Object>> rows = deptWorkOrderMapper.selectDailyCounts(
                safeDays, user.userType(), subCategory, region, user.regionCode());
        Map<String, Integer> countByDate = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String d = String.valueOf(row.get("stat_date"));
            Number c = (Number) row.get("stat_count");
            countByDate.put(d, c == null ? 0 : c.intValue());
        }

        // 生成完整日期序列（近 N 天含今天），缺失日期补 0 保证折线连续
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate today = LocalDate.now();
        List<String> dates = new ArrayList<>(safeDays);
        List<Integer> values = new ArrayList<>(safeDays);
        for (int i = safeDays - 1; i >= 0; i--) {
            String full = today.minusDays(i).format(fmt);
            dates.add(full.substring(5)); // 截取 MM-DD 供图表 x 轴展示
            values.add(countByDate.getOrDefault(full, 0));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dates", dates);
        result.put("values", values);
        return result;
    }

    private Object v(Map<String, Object> m, String camel, String snake) {
        if (m.containsKey(camel)) {
            return m.get(camel);
        }
        return m.get(snake);
    }
}
