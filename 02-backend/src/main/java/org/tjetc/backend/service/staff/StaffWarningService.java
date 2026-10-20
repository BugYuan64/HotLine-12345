package org.tjetc.backend.service.staff;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.entity.admin.ClassifyCategory;
import org.tjetc.backend.entity.admin.WarningRecord;
import org.tjetc.backend.entity.admin.WarningStreetDispatch;
import org.tjetc.backend.mapper.admin.ClassifyCategoryMapper;
import org.tjetc.backend.mapper.admin.WarningRecordMapper;
import org.tjetc.backend.mapper.admin.WarningStreetDispatchMapper;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门端/街道办端局部预警（需求 P1）：
 * - 部门端：仅可查看归属本部门（user_type 命中 department 字段）且已发送（SENT）的预警记录，
 *   数据范围按 JWT 中 user_type 自动过滤，前端不可越权（API 2.5）
 * - 街道办端：仅可查看分发给本街道（warning_street_dispatch.street_office = 账号辖区）的已发送预警，
 *   响应标记同样按分发关系校验
 */
@Service
@RequiredArgsConstructor
public class StaffWarningService {

    private final WarningRecordMapper recordMapper;
    private final WarningStreetDispatchMapper dispatchMapper;
    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final ClassifyCategoryMapper classifyCategoryMapper;

    /**
     * 部门端/街道办端预警记录分页：只读查询，结果始终限制在数据范围内
     * 一级分类由登录部门自动确定（职能部门 user_type 即一级分类；街道办按辖区分发关系过滤），前端不可传入
     */
    public PageData<Map<String, Object>> page(String subCategory, String warningLevel,
                                              String region, LocalDateTime startTime, LocalDateTime endTime,
                                              int page, int pageSize, LoginUser user) {
        // 分页查询本部门/本街道预警
        int offset = Math.max(0, (page - 1) * pageSize);
        if ("STREET".equals(user.userType())) {
            // 街道办：仅分发给本街道的已发送预警（辖区隔离）
            if (user.region() == null || user.region().isBlank()) {
                return PageData.of(List.of(), 0, page, pageSize);
            }
            List<Map<String, Object>> rows = recordMapper.selectStreetPage(
                    subCategory, warningLevel, region, startTime, endTime, offset, pageSize, user.region());
            long total = recordMapper.countStreetPage(
                    subCategory, warningLevel, region, startTime, endTime, user.region());
            return PageData.of(toMaps(rows), total, page, pageSize);
        }
        String dept = user.userType();
        List<Map<String, Object>> rows = recordMapper.selectStaffPage(
                dept, user.regionCode(), subCategory, warningLevel, region, startTime, endTime, offset, pageSize);
        long total = recordMapper.countStaffPage(
                dept, user.regionCode(), subCategory, warningLevel, region, startTime, endTime);
        return PageData.of(toMaps(rows), total, page, pageSize);
    }

    /** 行记录转 API 字段（部门端/街道办端共用同一输出口径，街道办端的响应字段来自分发记录） */
    private List<Map<String, Object>> toMaps(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> {
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
            m.put("dept_sent_time", v(r, "deptSentTime", "dept_sent_time"));
            m.put("dispatch_time", v(r, "dispatchTime", "dispatch_time"));
            return m;
        }).toList();
    }

    /**
     * 标记预警已响应（关闭响应闭环）：
     * - 部门端：仅限归属本部门的已发送记录，置预警记录 respond_status
     * - 街道办端：仅限分发到本街道的记录，置分发记录 respond_status，越权返回 40203
     */
    public Map<String, Object> respond(Long id, LoginUser user) {
        // 标记本部门/本街道预警已响应
        if ("STREET".equals(user.userType())) {
            WarningStreetDispatch dispatch = dispatchMapper.selectByWarningAndStreet(id, user.region());
            if (dispatch == null) {
                throw new BusinessException(ResultCode.REGION_FORBIDDEN, "该预警未分发至你辖区");
            }
            if ("RESPONDED".equals(dispatch.getRespondStatus())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "该预警已响应");
            }
            dispatchMapper.updateRespond(dispatch.getId(), LocalDateTime.now(), user.userId(),
                    user.jobNo() == null || user.jobNo().isBlank() ? null : user.jobNo());
            // 街道办响应后反向同步主表：全部分发街道办均已响应才置主表已响应（部门端/管理端列表读主表）
            recordMapper.updateRespondIfAllDispatched(id);
            return Map.of("responded", true);
        }
        WarningRecord record = requireOwned(id, user);
        if (!"SENT".equals(record.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅已发送预警可标记响应");
        }
        if (recordMapper.updateRespond(id) == 0) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "预警记录不存在");
        }
        // 部门端响应后反向同步分发表：该预警已分发的街道办记录全部置为已响应（街道办端列表读分发表）
        dispatchMapper.updateAllRespond(id, LocalDateTime.now());
        return Map.of("responded", true);
    }

    /** 二级分类建议列表：按一级分类返回字典表 classify_category 的二级标签，供部门端下拉筛选
     *  （改读字典而非工单表：工单未分类时下拉不至于只剩「全部」，选项稳定不随工单数据波动） */
    public List<String> listSubCategories(String category) {
        // 二级分类建议：字典全量查出后按一级分类过滤（40 条量级，内存过滤即可）
        return classifyCategoryMapper.selectAll().stream()
                .filter(c -> category == null || category.isBlank() || category.equals(c.getPrimaryCategory()))
                .map(ClassifyCategory::getSubLabel)
                .filter(label -> label != null && !label.isBlank())
                .sorted()
                .toList();
    }

    /** 工作台待办预警计数：部门端统计待跟进（未响应），街道办端统计待响应（分发未响应），口径与各自分页列表一致 */
    public long unrespondedCount(LoginUser user) {
        if ("STREET".equals(user.userType())) {
            if (user.region() == null || user.region().isBlank()) {
                return 0L;
            }
            return dispatchMapper.countUnrespondedByStreet(user.region());
        }
        return recordMapper.countUnrespondedByDept(user.userType(), user.regionCode());
    }

    /** 取记录并校验归属：归属判定以 department 为准（含本分类code=全市可见；含「区县:分类」复合键=仅该区县可见；
     *  仅推送 ADMIN（department 为空）的记录部门不可见；街道办可访问全部（另按分发关系校验） */
    private WarningRecord requireOwned(Long id, LoginUser user) {
        // 校验归属
        WarningRecord record = recordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "预警记录不存在");
        }
        if (!"STREET".equals(user.userType()) && !deptOwns(record, user)) {
            throw new BusinessException(ResultCode.REGION_FORBIDDEN, "越权访问其他部门预警数据");
        }
        return record;
    }

    /** 部门归属判定：department 逗号分隔条目中含本分类 code（全市）或「区县:分类」复合键（指定区县）即归属本部门 */
    private boolean deptOwns(WarningRecord record, LoginUser user) {
        // 判定部门归属
        String department = record.getDepartment();
        if (department == null || department.isBlank()) {
            return false;
        }
        String countyDept = (user.regionCode() == null ? "" : user.regionCode()) + ":" + user.userType();
        for (String t : department.split(",")) {
            String v = t.trim();
            if (v.equals(user.userType()) || v.equals(countyDept)) {
                return true;
            }
        }
        return false;
    }

    private Object v(Map<String, Object> m, String camel, String snake) {
        // 键名双兼容读取
        if (m.containsKey(camel)) {
            return m.get(camel);
        }
        return m.get(snake);
    }
}