package org.tjetc.backend.service.street;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.entity.common.WorkOrder;
import org.tjetc.backend.mapper.street.StreetWorkOrderMapper;
import org.tjetc.backend.mapper.staff.OrderDisposeAttachmentMapper;
import org.tjetc.backend.mapper.common.WorkOrderAppealMapper;
import org.tjetc.backend.mapper.citizen.AppealChildMapper;
import org.tjetc.backend.mapper.common.SysFileMapper;
import org.tjetc.backend.service.admin.FileService;
import org.tjetc.backend.service.admin.OrderLogService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 街道办端工单查询与详情（需求 15 权限内工单查询 / API 9.5/9.12）——三端数据解耦：
 * 只读街道办端副本表 street_work_order（工单未分派至本辖区即副本不存在，天然不可见），
 * 不同辖区对应不同街道办账号，按 JWT 中 region 自动过滤数据范围，前端不可越权（API 2.5）。
 * 时间线只读街道办端日志表 street_order_log。
 */
@Service
@RequiredArgsConstructor
public class StreetOrderService {

    private final StreetWorkOrderMapper streetWorkOrderMapper;
    private final OrderLogService orderLogService;
    private final OrderDisposeAttachmentMapper disposeAttachmentMapper;
    private final WorkOrderAppealMapper workOrderAppealMapper;
    private final AppealChildMapper appealChildMapper;
    private final SysFileMapper sysFileMapper;
    private final FileService fileService;

    /**
     * 权限内工单查询（API 9.5）：本辖区工单（street_office 前缀匹配）
     */
    public PageData<Map<String, Object>> page(String orderNo, String keyword, String status,
                                              LocalDateTime startTime, LocalDateTime endTime,
                                              int page, int pageSize, LoginUser user) {
        String streetOffice = user.region();
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> rows = streetWorkOrderMapper.selectPageStreet(
                orderNo, keyword, status, startTime, endTime, streetOffice, offset, pageSize);
        long total = streetWorkOrderMapper.countPageStreet(
                orderNo, keyword, status, startTime, endTime, streetOffice);
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
     * 权限内工单详情（API 9.12）：含命中关键词、处置附件与街道办端时间线
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
        // 诉求附件（市民提交时上传，MinIO 预签名下载链接）
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
        // 三端数据解耦：街道办端时间线只读 street_order_log
        data.put("timeline", orderLogService.timeline(id, OrderLogService.TimelineEnd.STREET));
        return data;
    }

    /** 取街道办端副本并校验数据范围：越权访问返回 40203（供附件上传等接口复用，防横向越权）
     *  street_office 统一带「街道办」后缀的历史数据兼容：去后缀后与账号 region 比较 */
    public WorkOrder requireScope(Long id, LoginUser user) {
        WorkOrder order = streetWorkOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "工单不存在或未分派至本辖区");
        }
        String office = order.getStreetOffice();
        String officeRegion = office != null && office.endsWith("街道办")
                ? office.substring(0, office.length() - 3) : office;
        if (officeRegion == null || !officeRegion.equals(user.region())) {
            throw new BusinessException(ResultCode.REGION_FORBIDDEN, "越权访问他人辖区工单数据");
        }
        return order;
    }

    /**
     * 街道办工作台首页统计（仅本辖区工单，读街道办端副本表）。
     * 返回 order_total / pending_handle / pending_verify / pending_assign /
     * pending_audit / month_done / avg_score（pending_delay 恒为 0，延期审批仅部门端）
     */
    public Map<String, Object> workbenchSummary(LoginUser user) {
        String region = user.region();
        LocalDate now = LocalDate.now();
        Map<String, Object> m = new LinkedHashMap<>(
                streetWorkOrderMapper.workbenchSummaryStreet(region, now.getYear(), now.getMonthValue()));
        m.put("order_total", num(m, "order_total"));
        m.put("pending_handle", num(m, "pending_handle"));
        m.put("pending_verify", num(m, "pending_verify"));
        m.put("pending_assign", num(m, "pending_assign"));
        m.put("pending_audit", num(m, "pending_audit"));
        m.put("month_done", num(m, "month_done"));
        // 待审批延期仅部门端有意义（街道办端不展示，恒为 0）
        m.put("pending_delay", 0L);
        double score = ((Number) m.get("avg_score")).doubleValue();
        m.put("avg_score", Math.round(score * 100L) / 100.0);
        return m;
    }

    /** 归一化 work_order 聚合计数：SQL 空结果可能返回 null */
    private long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }

    private Object v(Map<String, Object> m, String camel, String snake) {
        if (m.containsKey(camel)) {
            return m.get(camel);
        }
        return m.get(snake);
    }
}
