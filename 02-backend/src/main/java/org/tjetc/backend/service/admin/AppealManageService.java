package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.admin.AppealCreateRequest;
import org.tjetc.backend.dto.admin.AppealUpdateRequest;
import org.tjetc.backend.entity.citizen.AppealChild;
import org.tjetc.backend.entity.citizen.AppealParent;
import org.tjetc.backend.entity.common.WorkOrder;
import org.tjetc.backend.mapper.citizen.AppealParentMapper;
import org.tjetc.backend.mapper.citizen.AppealChildMapper;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;
import org.tjetc.backend.mapper.common.WorkOrderAppealMapper;
import org.tjetc.backend.mapper.common.SysFileMapper;
import org.tjetc.backend.service.citizen.PublicAppealService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端诉求管理（需求 2.3 诉求管理页 / API 6.1 诉求查询与追溯）：
 * 市民原始诉求统一查询与追溯，展示自动清洗留痕可对比、同源诉求自动标记关联；
 * 并支持管理员对诉求的新增、编辑、删除（新增：标题/状态/诉求详情必填，其余选填）。
 * 父诉求与子诉求分表存储，通过 scope（parent/child）区分操作目标。
 */
@Service
@RequiredArgsConstructor
public class AppealManageService {

    private final AppealParentMapper appealParentMapper;
    private final AppealChildMapper appealChildMapper;
    private final SysFileMapper sysFileMapper;
    private final org.tjetc.backend.service.admin.FileService fileService;
    private final WorkOrderAppealMapper workOrderAppealMapper;
    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final OrderService orderService;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 诉求分页查询（API 6.1 GET）：诉求编号模糊、诉求状态、关键词过滤；scope=parent/child 区分父/子诉求 */
    public PageData<Map<String, Object>> page(String appealNo, String status,
                                              String keyword, String scope, int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        if ("child".equals(scope)) {
            List<AppealChild> rows = appealChildMapper.selectPage(appealNo, status, keyword, offset, pageSize);
            long total = appealChildMapper.countPage(appealNo, status, keyword);
            return PageData.of(rows.stream().map(this::toChildVo).toList(), total, page, pageSize);
        }
        List<AppealParent> rows = appealParentMapper.selectPage(appealNo, status, keyword, offset, pageSize);
        long total = appealParentMapper.countPage(appealNo, status, keyword);
        return PageData.of(rows.stream().map(this::toParentVo).toList(), total, page, pageSize);
    }

    /**
     * 诉求附件列表（市民提交时上传，MinIO 预签名下载链接）：
     * scope=parent 直查该父诉求附件；scope=child 查其所属父诉求附件（附件在提交时挂父诉求）。
     * 每个文件带限时预签名 URL，前端点击下载/查看。
     */
    public List<Map<String, Object>> attachments(Long id, String scope) {
        Long appealId = id;
        if ("child".equals(scope)) {
            AppealChild child = appealChildMapper.selectById(id);
            if (child == null) {
                throw new BusinessException(ResultCode.DATA_NOT_FOUND, "子诉求不存在");
            }
            appealId = child.getParentId();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (org.tjetc.backend.entity.common.SysFile f : sysFileMapper.selectByAppealId(appealId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file_id", f.getId());
            m.put("file_name", f.getFileName());
            m.put("file_type", f.getFileType());
            m.put("file_size", f.getFileSize());
            m.put("url", fileService.presignedUrl(f.getFileUrl()));
            list.add(m);
        }
        return list;
    }

    /** 查询某父诉求的全部子诉求（诉求管理子诉求页签/父诉求展开） */
    public List<Map<String, Object>> children(Long parentId) {
        return appealChildMapper.selectChildrenByParentId(parentId).stream().map(this::toChildVo).toList();
    }

    /** 查询子诉求对应工单详情（子诉求列表「查看工单」弹窗用） */
    public Map<String, Object> getChildOrder(Long childId) {
        AppealChild child = appealChildMapper.selectById(childId);
        if (child == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "子诉求不存在");
        }
        Long orderId = workOrderAppealMapper.selectOrderIdByAppealId(childId);
        if (orderId == null) {
            return Map.of("has_order", false, "child_appeal_no", child.getAppealNo());
        }
        WorkOrder order = adminWorkOrderMapper.selectById(orderId);
        if (order == null) {
            return Map.of("has_order", false, "child_appeal_no", child.getAppealNo());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("has_order", true);
        m.put("child_appeal_no", child.getAppealNo());
        m.put("id", order.getId());
        m.put("order_no", order.getOrderNo());
        m.put("title", order.getTitle());
        m.put("content", order.getContent());
        m.put("category", order.getCategory());
        m.put("sub_category", order.getSubCategory());
        m.put("department", order.getDepartment());
        m.put("status", order.getStatus());
        m.put("level", order.getLevel());
        m.put("attention_count", order.getAttentionCount());
        m.put("confidence", order.getConfidence());
        m.put("region", order.getRegion());
        m.put("create_time", order.getCreateTime());
        // 诉求附件（来源子诉求所属父诉求的市民上传附件；MinIO 预签名下载链接）
        java.util.List<Map<String, Object>> appealFiles = new java.util.ArrayList<>();
        if (child.getParentId() != null) {
            for (org.tjetc.backend.entity.common.SysFile f : sysFileMapper.selectByAppealId(child.getParentId())) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("file_id", f.getId());
                fm.put("file_name", f.getFileName());
                fm.put("url", fileService.presignedUrl(f.getFileUrl()));
                appealFiles.add(fm);
            }
        }
        m.put("appeal_attachments", appealFiles);
        return m;
    }

    /** 新增诉求：标题/状态/诉求详情必填，其余选填；parent_id 为空创建父诉求，否则创建子诉求 */
    @Transactional
    public Map<String, Object> create(AppealCreateRequest req, LoginUser user) {
        if (req.parentId() != null) {
            return createChild(req, user);
        }
        return createParent(req, user);
    }

    private Map<String, Object> createParent(AppealCreateRequest req, LoginUser user) {
        AppealParent a = new AppealParent();
        a.setAppealNo(nextParentNo());
        a.setTitle(req.title());
        a.setContent(req.content());
        a.setStatus(req.status());
        a.setCategory(trimToNull(req.category()));
        a.setCitizenName(trimToNull(req.citizenName()));
        // citizen_phone 非空约束：未填写时落空字符串，列表展示仍走脱敏
        a.setCitizenPhone(trimToNull(req.citizenPhone()) == null ? "" : req.citizenPhone().trim());
        a.setDistrict(trimToNull(req.district()));
        a.setStreet(trimToNull(req.street()));
        a.setCommunity(trimToNull(req.community()));
        a.setAddressDetail(trimToNull(req.addressDetail()));
        a.setRegion(PublicAppealService.joinRegion(req.district(), req.street(), req.addressDetail()));
        a.setBroadFlag(Boolean.TRUE.equals(req.broadFlag()) ? 1 : 0);
        appealParentMapper.insert(a);

        auditLogService.recordOperation("ADMIN-" + user.jobNo(), "appeal", "新增父诉求：" + a.getAppealNo());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", a.getId());
        data.put("appeal_no", a.getAppealNo());
        return data;
    }

    private Map<String, Object> createChild(AppealCreateRequest req, LoginUser user) {
        AppealChild a = new AppealChild();
        a.setAppealNo(nextChildNo());
        a.setParentId(req.parentId());
        a.setTitle(req.title());
        a.setContent(req.content());
        a.setStatus(req.status());
        a.setCategory(trimToNull(req.category()));
        a.setCitizenName(trimToNull(req.citizenName()));
        // citizen_phone 非空约束：未填写时落空字符串，列表展示仍走脱敏
        a.setCitizenPhone(trimToNull(req.citizenPhone()) == null ? "" : req.citizenPhone().trim());
        a.setDistrict(trimToNull(req.district()));
        a.setStreet(trimToNull(req.street()));
        a.setCommunity(trimToNull(req.community()));
        a.setAddressDetail(trimToNull(req.addressDetail()));
        a.setRegion(PublicAppealService.joinRegion(req.district(), req.street(), req.addressDetail()));
        a.setBroadFlag(Boolean.TRUE.equals(req.broadFlag()) ? 1 : 0);
        appealChildMapper.insert(a);

        auditLogService.recordOperation("ADMIN-" + user.jobNo(), "appeal", "新增子诉求：" + a.getAppealNo());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", a.getId());
        data.put("appeal_no", a.getAppealNo());
        return data;
    }

    /** 编辑诉求：标题/状态/诉求详情等覆盖更新，手机号留空表示保持不变（列表展示时已脱敏）；scope 区分父/子诉求 */
    @Transactional
    public Map<String, Object> update(Long id, String scope, AppealUpdateRequest req, LoginUser user) {
        if ("child".equals(scope)) {
            AppealChild exist = requireAppealChild(id);
            AppealChild a = new AppealChild();
            a.setId(id);
            a.setTitle(req.title() != null ? req.title() : exist.getTitle());
            a.setContent(req.content() != null ? req.content() : exist.getContent());
            a.setStatus(req.status() != null ? req.status() : exist.getStatus());
            a.setCategory(req.category() != null ? trimToNull(req.category()) : exist.getCategory());
            a.setCitizenName(trimToNull(req.citizenName()));
            a.setDistrict(trimToNull(req.district()));
            a.setStreet(trimToNull(req.street()));
            a.setCommunity(trimToNull(req.community()));
            a.setAddressDetail(trimToNull(req.addressDetail()));
            a.setBroadFlag(req.broadFlag() != null
                    ? (Boolean.TRUE.equals(req.broadFlag()) ? 1 : 0) : exist.getBroadFlag());
            // 手机号以提交值为准（前端回填原值，去掉“留空表示不变”）
            a.setCitizenPhone(req.citizenPhone() == null ? null : req.citizenPhone().trim());
            appealChildMapper.update(a);
            auditLogService.recordOperation("ADMIN-" + user.jobNo(), "appeal", "编辑子诉求：" + exist.getAppealNo());
        } else {
            AppealParent exist = requireAppealParent(id);
            AppealParent a = new AppealParent();
            a.setId(id);
            a.setTitle(req.title() != null ? req.title() : exist.getTitle());
            a.setContent(req.content() != null ? req.content() : exist.getContent());
            a.setStatus(req.status() != null ? req.status() : exist.getStatus());
            a.setCategory(req.category() != null ? trimToNull(req.category()) : exist.getCategory());
            a.setCitizenName(trimToNull(req.citizenName()));
            a.setDistrict(trimToNull(req.district()));
            a.setStreet(trimToNull(req.street()));
            a.setCommunity(trimToNull(req.community()));
            a.setAddressDetail(trimToNull(req.addressDetail()));
            a.setBroadFlag(req.broadFlag() != null
                    ? (Boolean.TRUE.equals(req.broadFlag()) ? 1 : 0) : exist.getBroadFlag());
            // 手机号以提交值为准（前端回填原值，去掉“留空表示不变”）
            a.setCitizenPhone(req.citizenPhone() == null ? null : req.citizenPhone().trim());
            appealParentMapper.update(a);
            auditLogService.recordOperation("ADMIN-" + user.jobNo(), "appeal", "编辑父诉求：" + exist.getAppealNo());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        return data;
    }

    /**
     * 删除诉求（软删除：数据保留 is_deleted=1，列表/查询过滤不显示）：
     * - 父诉求：存在子诉求则不可删除；
     * - 子诉求：关联唯一工单（attentionCount<=1）不可删除；非唯一则解绑工单并降关注度/等级后删除
     */
    @Transactional
    public Map<String, Object> delete(Long id, String scope, LoginUser user) {
        if ("child".equals(scope)) {
            AppealChild exist = requireAppealChild(id);
            Long orderId = workOrderAppealMapper.selectOrderIdByAppealId(id);
            if (orderId != null) {
                WorkOrder order = adminWorkOrderMapper.selectById(orderId);
                int attention = order != null && order.getAttentionCount() != null
                        ? order.getAttentionCount() : 1;
                if (order != null && attention <= 1) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "该子诉求已关联唯一工单，不可删除");
                }
                if (order != null) {
                    orderService.demergeChildAppeal(id, orderId, "ADMIN-" + user.jobNo());
                }
            }
            appealChildMapper.deleteById(id);
            auditLogService.recordOperation("ADMIN-" + user.jobNo(), "appeal", "删除子诉求：" + exist.getAppealNo());
        } else {
            AppealParent exist = requireAppealParent(id);
            if (appealChildMapper.countChildrenByParentId(id) > 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "该父诉求存在关联子诉求，不可删除");
            }
            appealParentMapper.deleteById(id);
            auditLogService.recordOperation("ADMIN-" + user.jobNo(), "appeal", "删除父诉求：" + exist.getAppealNo());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        return data;
    }

    /** 父诉求 -> 追溯视图对象：检测类别转为数组便于前端展示 */
    private Map<String, Object> toParentVo(AppealParent a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("appeal_no", a.getAppealNo());
        m.put("title", a.getTitle());
        m.put("content", a.getContent());
        m.put("clean_log_id", null);
        m.put("status", a.getStatus());
        m.put("detected_categories", List.of());
        m.put("event_type", null);
        m.put("category", a.getCategory());
        m.put("parent_id", null);
        m.put("same_source_ids", List.of());
        m.put("citizen_name", a.getCitizenName());
        m.put("citizen_phone", a.getCitizenPhone());
        m.put("district", a.getDistrict());
        m.put("street", a.getStreet());
        m.put("community", a.getCommunity());
        m.put("address_detail", a.getAddressDetail());
        m.put("broad_flag", a.getBroadFlag());
        m.put("create_time", a.getCreateTime());
        return m;
    }

    /** 子诉求 -> 追溯视图对象：同源关联转为数组便于前端展示 */
    private Map<String, Object> toChildVo(AppealChild a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("appeal_no", a.getAppealNo());
        m.put("title", a.getTitle());
        m.put("content", a.getContent());
        m.put("clean_log_id", a.getCleanLogId());
        m.put("status", a.getStatus());
        m.put("detected_categories", List.of());
        m.put("event_type", null);
        m.put("category", a.getCategory());
        // AI 两阶段分类结果（队列1 落库）：一级之外的二级/置信度/复核标记（列表二级分类列显示用）
        m.put("sub_category", a.getSubCategory());
        m.put("confidence", a.getConfidence());
        m.put("need_review", a.getNeedReview());
        m.put("parent_id", a.getParentId());
        m.put("same_source_ids", List.of());
        m.put("citizen_name", a.getCitizenName());
        m.put("citizen_phone", a.getCitizenPhone());
        m.put("district", a.getDistrict());
        m.put("street", a.getStreet());
        m.put("community", a.getCommunity());
        m.put("address_detail", a.getAddressDetail());
        m.put("broad_flag", a.getBroadFlag());
        m.put("create_time", a.getCreateTime());
        // 子诉求关联工单信息（前端删除前提示「关注度/等级将下降」用）
        Long linkedOrderId = workOrderAppealMapper.selectOrderIdByAppealId(a.getId());
        if (linkedOrderId != null) {
            WorkOrder linkedOrder = adminWorkOrderMapper.selectById(linkedOrderId);
            m.put("linked_order_id", linkedOrderId);
            m.put("order_attention_count", linkedOrder == null ? null : linkedOrder.getAttentionCount());
        }
        return m;
    }

    private AppealParent requireAppealParent(Long id) {
        AppealParent a = appealParentMapper.selectById(id);
        if (a == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "父诉求不存在");
        }
        return a;
    }

    private AppealChild requireAppealChild(Long id) {
        AppealChild a = appealChildMapper.selectById(id);
        if (a == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "子诉求不存在");
        }
        return a;
    }

    /** 空白字符串转 null，避免空串落库 */
    private String trimToNull(String s) {
        return s == null ? null : (s.isBlank() ? null : s.trim());
    }

    /** 父诉求编号：AP + 时间戳 + 序号（与市民端 PublicAppealService 一致），父表内唯一。
     *  synchronized：同秒并发共享计数器会生成重复编号；跨实例由唯一索引兜底。 */
    private synchronized String nextParentNo() {
        String prefix = "AP" + LocalDateTime.now().format(NO_TS);
        long seq = appealParentMapper.countByPrefix(prefix) + 1;
        return prefix + String.format("%03d", seq);
    }

    /** 子诉求编号：AS + 时间戳 + 序号，子表内唯一（同秒并发由 synchronized + 唯一索引保障） */
    private synchronized String nextChildNo() {
        String prefix = "AS" + LocalDateTime.now().format(NO_TS);
        long seq = appealChildMapper.countByPrefix(prefix) + 1;
        return prefix + String.format("%03d", seq);
    }

    /** 逗号分隔的类别字符串 -> 类别数组（如 "URBAN,PROPERTY" -> ["URBAN","PROPERTY"]） */
    private List<String> splitCsv(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    /** 逗号分隔的同源诉求ID -> ID 数组，忽略空值与非法数字 */
    private List<Long> splitIds(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                try {
                    ids.add(Long.parseLong(t));
                } catch (NumberFormatException ignore) {
                    // 忽略非法数字，保留其余合法 ID
                }
            }
        }
        return ids;
    }
}