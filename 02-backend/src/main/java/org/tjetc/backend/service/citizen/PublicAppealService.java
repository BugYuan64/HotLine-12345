package org.tjetc.backend.service.citizen;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.citizen.AppealSubmitRequest;
import org.tjetc.backend.entity.admin.SensitiveEvent;
import org.tjetc.backend.entity.citizen.AppealParent;
import org.tjetc.backend.mapper.citizen.AppealParentMapper;
import org.tjetc.backend.mapper.common.SysFileMapper;
import org.tjetc.backend.service.admin.AuditLogService;
import org.tjetc.backend.service.admin.SensitiveEventService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 公众端诉求提交服务（API 4.1）：
 * 市民提交诉求（父诉求），生成唯一诉求编号（AP），初始状态 PENDING_AI（待AI检测）。
 * AI 类别检测与附件解析在 P2 批次接入，当前仅落库主诉求数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicAppealService {

    private final AppealParentMapper appealParentMapper;
    private final SensitiveEventService sensitiveEventService;
    private final AuditLogService auditLogService;
    private final SysFileMapper sysFileMapper;
    private final org.tjetc.backend.mapper.citizen.CitizenNoticeMapper citizenNoticeMapper;
    // 市民端列表聚合：子诉求及其工单状态（部门处理中/可评价等业务状态推导）
    private final org.tjetc.backend.mapper.citizen.AppealChildMapper appealChildMapper;
    private final org.tjetc.backend.mapper.admin.AdminWorkOrderMapper adminWorkOrderMapper;
    // 市民端删除条件判断：工单是否已评价
    private final org.tjetc.backend.mapper.citizen.OrderEvaluateMapper orderEvaluateMapper;

    private static final DateTimeFormatter NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 评价窗口天数：处置完成后 7 天内可评价，超期未评价由部门审核（删除可用条件与之一致） */
    private static final int EVALUATE_WINDOW_DAYS = 7;

    /** 街道办处置完成后（市民侧生命周期进入评价/审核环节）的工单状态集合 */
    private static final List<String> DISPOSED_ORDER_STATUS = List.of(
            "PENDING_EVALUATE", "STREET_DONE", "EVALUATED", "DEPT_AUDITED", "CLOSED", "ARCHIVED");

    private String phoneOf(LoginUser user) {
        if (user == null || user.phone() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        return user.phone();
    }

    /**
     * 提交诉求（API 4.1）：先做敏感前置检测——命中即剥离入敏感事件表（不生成父诉求/不拆分/不建单）；
     * 未命中则落库父诉求记录，返回诉求编号与初始状态。
     */
    @Transactional
    public Map<String, Object> submit(AppealSubmitRequest request, LoginUser user) {
        // 宽泛诉求（全市范围）无需地址；否则区县与详细地址均为必填
        if (!Boolean.TRUE.equals(request.broadFlag())) {
            if (request.district() == null || request.district().isBlank()) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "请选择区县");
            }
            if (request.addressDetail() == null || request.addressDetail().isBlank()) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "请填写详细地址");
            }
        }

        // 敏感前置检测（全部剥离模式）：命中的诉求只落 sensitive_event 快照，
        // 不进入父诉求/拆分/建单流程；附件保持 appeal_id 悬挂不绑定
        Optional<SensitiveEvent> hit = sensitiveEventService.detectAtSubmit(request, phoneOf(user));
        if (hit.isPresent()) {
            auditLogService.recordOperation("CITIZEN", "appeal-submit",
                    "市民提交诉求（敏感已剥离）：" + hit.get().getAppealNo());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sensitive", true);
            return data;
        }

        AppealParent appeal = new AppealParent();
        appeal.setAppealNo(nextAppealNo());
        // 标题由管理端收到诉求后 AI 总结生成（title 为空即未生成，标题阶段据此判断）
        appeal.setContent(request.content());
        // 市民提交自带类别（8大类 code 或 OTHER）
        appeal.setCategory(trimToNull(request.category()));
        appeal.setCitizenPhone(phoneOf(user));
        appeal.setProvince(request.province());
        appeal.setCity(request.city());
        appeal.setDistrict(request.district());
        appeal.setStreet(request.street());
        appeal.setCommunity(request.community());
        appeal.setAddressDetail(request.addressDetail());
        appeal.setRegion(joinRegion(request.district(), request.street(), request.addressDetail()));
        appeal.setBroadFlag(Boolean.TRUE.equals(request.broadFlag()) ? 1 : 0);
        appeal.setStatus("TITLE_PENDING");   // 待AI生成标题（标题阶段自动生成，不受开关影响）
        appealParentMapper.insert(appeal);
        // 四端数据解耦：市民端诉求独立落库（citizen_appeal，与管理端诉求域解耦，同事务）
        org.tjetc.backend.entity.common.CitizenAppeal ca = new org.tjetc.backend.entity.common.CitizenAppeal();
        ca.setAppealNo(appeal.getAppealNo());
        ca.setParentId(appeal.getId());
        ca.setCitizenUserId(user.userId());
        // 市民提交不填姓名（与 appeal_parent 一致），留空由后续环节补充
        ca.setCitizenPhone(appeal.getCitizenPhone());
        ca.setContent(request.content());
        ca.setProvince(request.province());
        ca.setCity(request.city());
        ca.setDistrict(request.district());
        ca.setStreet(request.street());
        ca.setCommunity(request.community());
        ca.setAddressDetail(request.addressDetail());
        ca.setBroadFlag(appeal.getBroadFlag());
        ca.setSubmitTime(java.time.LocalDateTime.now());
        citizenNoticeMapper.insertAppeal(ca);
        // 附件关联：市民提交时上传的文件绑定到父诉求（update 条件限定 appeal_id IS NULL，防重复/越权绑定他人文件）
        if (request.attachmentIds() != null && !request.attachmentIds().isEmpty()) {
            int bound = 0;
            for (Long fileId : request.attachmentIds()) {
                if (fileId == null) continue;
                bound += sysFileMapper.updateAppealId(fileId, appeal.getId());
            }
            if (bound > 0) {
                log.info("诉求 {} 绑定附件 {} 个", appeal.getAppealNo(), bound);
            }
        }
        // 环节留痕：市民提交诉求（需求：每环节日志记录）
        auditLogService.recordOperation("CITIZEN", "appeal-submit",
                "市民提交诉求：" + appeal.getAppealNo()
                        + "（类别=" + (appeal.getCategory() == null ? "未填" : appeal.getCategory())
                        + "，区县=" + (appeal.getDistrict() == null || appeal.getDistrict().isBlank() ? "全市范围" : appeal.getDistrict()) + "）");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", appeal.getId());
        data.put("appeal_no", appeal.getAppealNo());
        data.put("status", appeal.getStatus());
        data.put("detected_categories", List.of());
        return data;
    }

    private static String trimToNull(String s) {
        return s == null ? null : (s.isBlank() ? null : s.trim());
    }

    /** 拼接完整地址：district + street + address_detail，空格分隔，空片段跳过 */
    public static String joinRegion(String district, String street, String addressDetail) {
        StringBuilder sb = new StringBuilder();
        appendRegionPart(sb, district);
        appendRegionPart(sb, street);
        appendRegionPart(sb, addressDetail);
        return sb.toString().isEmpty() ? null : sb.toString();
    }

    private static void appendRegionPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(part.trim());
    }

    /** 诉求编号：AP + 时间戳 + 序号（如 AP20260825143012 + 3位序号），父表内唯一。
     *  synchronized：同秒并发提交共享同一计数器（countByPrefix），不加锁会生成重复编号；
     *  跨实例部署时仍由 appeal_parent.appeal_no 唯一索引兜底（冲突抛异常由调用方回滚重试）。 */
    private synchronized String nextAppealNo() {
        String prefix = "AP" + LocalDateTime.now().format(NO_TS);
        long seq = appealParentMapper.countByPrefix(prefix) + 1;
        return prefix + String.format("%03d", seq);
    }

    /**
     * 查询当前账号的诉求列表（市民端首页"办理进度" / "查看全部诉求"）：
     * 每条附带子诉求工单聚合后的业务状态（受理中/XX部门处理中/可评价/已评价/已办结），
     * 不再暴露标题生成/拆分等技术状态；拆分后各子诉求独立展示各自进度状态
     * @param user  当前登录用户（通过 UserContext.get() 获取）
     * @param page  页码（从1开始）
     * @param pageSize 每页条数
     * @param appealNo 诉求编号精确过滤（可空；空为查全部）
     * @return 分页数据（list + total + page + pageSize）
     */
    public PageData<Map<String, Object>> listByCurrentUser(LoginUser user, int page, int pageSize, String appealNo) {
        String phone = phoneOf(user);
        int offset = Math.max(0, (page - 1) * pageSize);
        List<AppealParent> parents = appealParentMapper.selectPageByPhone(phone, appealNo, offset, pageSize);
        long total = appealParentMapper.countPageByPhone(phone, appealNo);

        // 批量聚合：父诉求 → 子诉求 → 工单当前状态（一次 JOIN，避免逐条 N+1）
        Map<Long, List<Map<String, Object>>> childrenByParent = new java.util.HashMap<>();
        if (!parents.isEmpty()) {
            List<Long> parentIds = parents.stream().map(AppealParent::getId).toList();
            for (Map<String, Object> row : appealChildMapper.selectChildrenWithOrderByParentIds(parentIds)) {
                Long pid = ((Number) row.get("parentId")).longValue();
                childrenByParent.computeIfAbsent(pid, k -> new java.util.ArrayList<>()).add(row);
            }
        }

        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (AppealParent p : parents) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("appealNo", p.getAppealNo());
            m.put("title", p.getTitle());
            m.put("content", p.getContent());
            m.put("category", p.getCategory());
            m.put("district", p.getDistrict());
            m.put("street", p.getStreet());
            m.put("status", p.getStatus());
            m.put("createTime", p.getCreateTime());

            List<Map<String, Object>> children = childrenByParent.getOrDefault(p.getId(), new java.util.ArrayList<>());
            // legacy 兜底：无子诉求但父诉求直挂工单（旧数据），以父诉求为行展示该工单状态
            if (children.isEmpty()) {
                org.tjetc.backend.entity.common.WorkOrder legacy =
                        adminWorkOrderMapper.selectBySourceAppealId(p.getId());
                if (legacy != null) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("appealNo", p.getAppealNo());
                    row.put("title", p.getTitle());
                    row.put("content", p.getContent());
                    row.put("orderId", legacy.getId());
                    row.put("orderStatus", legacy.getStatus());
                    row.put("department", legacy.getDepartment());
                    row.put("streetOffice", legacy.getStreetOffice());
                    row.put("isArchived", legacy.getIsArchived());
                    // 删除条件判断所需（与子诉求聚合行同口径）
                    row.put("disposeTime", legacy.getDisposeTime());
                    row.put("closeTime", legacy.getCloseTime());
                    row.put("evaluated", orderEvaluateMapper.selectByOrderId(legacy.getId()) != null);
                    children.add(row);
                }
            }

            // 逐子诉求推导业务状态（受理中/部门处理中/可评价/已评价/已办结）
            List<Map<String, Object>> childViews = new java.util.ArrayList<>();
            List<String> processingOrgs = new java.util.ArrayList<>();
            int worst = 5; // 整体状态取最落后（数字越小越靠前）：1处理中 2受理中 3可评价 4已评价 5已办结
            // 删除可用：全部子诉求工单均已评价（或处置完成已超 7 天评价窗口）才可删；无任何工单不可删
            boolean deletable = !children.isEmpty();
            for (Map<String, Object> c : children) {
                // is_archived 经 MyBatis 查询可能被映射为 Boolean（tinyint(1)），统一转 int 避免类型转换异常
                Object archivedRaw = c.get("isArchived");
                int archivedVal = (archivedRaw instanceof Boolean b) ? (b ? 1 : 0)
                        : (archivedRaw instanceof Number n) ? n.intValue() : 0;
                String biz = orderBiz((String) c.get("orderStatus"), archivedVal);
                String org = (String) c.get("department");
                String bizText = bizText(biz, org);
                Map<String, Object> cv = new LinkedHashMap<>();
                cv.put("appealNo", c.get("appealNo"));
                cv.put("title", c.get("title") != null && !((String) c.get("title")).isBlank()
                        ? c.get("title") : c.get("content"));
                cv.put("bizStatus", biz);
                cv.put("bizText", bizText);
                // 承办部门代码（如 URBAN）：前端经 classify/primaries 翻译成中文部门名（城管部门处理中）
                cv.put("department", org);
                childViews.add(cv);
                int rank = bizRank(biz);
                worst = Math.min(worst, rank);
                if (rank <= 2 && org != null && !org.isBlank() && !processingOrgs.contains(org)) {
                    processingOrgs.add(org);
                }
                if (!rowDeletable(c)) {
                    deletable = false;
                }
            }

            // 整体业务状态：未拆分/未建单 → 受理中；已撤回单独口径
            String bizStatus;
            String bizText;
            if ("WITHDRAWN".equals(p.getStatus())) {
                bizStatus = "WITHDRAWN";
                bizText = "已撤回";
            } else if (children.isEmpty()) {
                bizStatus = "DISPATCHING";
                bizText = "受理中";
            } else {
                bizStatus = switch (worst) {
                    case 1 -> "PROCESSING";
                    case 2 -> "DISPATCHING";
                    case 3 -> "EVALUABLE";
                    case 4 -> "EVALUATED";
                    default -> "DONE";
                };
                if (worst == 1 && !processingOrgs.isEmpty()) {
                    bizText = String.join("、", processingOrgs) + "处理中";
                } else {
                    bizText = bizText(bizStatus, null);
                }
            }
            m.put("children", childViews);
            m.put("bizStatus", bizStatus);
            m.put("bizText", bizText);
            // 整体承办部门代码列表（前端翻译中文名；处理中时显示"XX部门处理中"）
            m.put("departments", processingOrgs);
            // 删除可用标记：全部子诉求工单已评价（或处置满 7 天超评价窗口）为 true，前端据此启用删除按钮
            m.put("deletable", deletable);
            list.add(m);
        }
        return PageData.of(list, total, page, pageSize);
    }

    /** 工单状态 → 市民端业务状态：处理中/受理中/可评价/已评价/已办结 */
    private String orderBiz(String orderStatus, Integer isArchived) {
        if (orderStatus == null) {
            return "DISPATCHING";   // 子诉求尚未生成工单
        }
        if (isArchived != null && isArchived == 1) {
            return "DONE";
        }
        return switch (orderStatus) {
            // 待管理端分派/重新分派/部门驳回回管理端：尚无部门承接
            case "PENDING_DISPATCH", "PENDING_REASSIGN", "DEPT_REJECTED" -> "DISPATCHING";
            // 部门承接后的全部在办状态（含部门确认、分派街道办、街道办处理、驳回退回流转）
            case "PENDING_DEPT_PROCESS", "PENDING_DEPT_ASSIGN", "STREET_REJECTED",
                 "PENDING_UPDATE", "PENDING_STREET_PROCESS" -> "PROCESSING";
            // 街道办处置完成：市民可评价
            case "PENDING_EVALUATE", "STREET_DONE" -> "EVALUABLE";
            case "EVALUATED" -> "EVALUATED";
            case "DEPT_AUDITED", "CLOSED" -> "DONE";
            default -> "PROCESSING";
        };
    }

    /** 业务状态显示文案：处理中时优先显示承办部门名 */
    private String bizText(String biz, String org) {
        return switch (biz) {
            case "PROCESSING" -> (org == null || org.isBlank() ? "" : org) + "处理中";
            case "DISPATCHING" -> "受理中";
            case "EVALUABLE" -> "可评价";
            case "EVALUATED" -> "已评价";
            case "DONE" -> "已办结";
            case "WITHDRAWN" -> "已撤回";
            default -> "处理中";
        };
    }

    /** 业务状态排序（越小越靠前）：用于整体状态取最落后子诉求 */
    private int bizRank(String biz) {
        return switch (biz) {
            case "PROCESSING" -> 1;
            case "DISPATCHING" -> 2;
            case "EVALUABLE" -> 3;
            case "EVALUATED" -> 4;
            default -> 5;
        };
    }

    /**
     * 查询当前账号的诉求总数（首页"办理进度"统计）
     */
    public long countByCurrentUser(LoginUser user) {
        return appealParentMapper.countPageByPhone(phoneOf(user), null);
    }

    /**
     * 市民删除诉求（软删除，API：DELETE /public/appeal/{appealNo}）：
     * 仅当全部子诉求工单均已评价、或街道办处置完成已超 7 天未评价（超评价窗口，部门自动审核口径）时允许；
     * 父诉求与全部子诉求一并置 is_deleted=1 —— 数据仅留存库中（状态被标记），市民端列表/查询不再展示。
     */
    @Transactional
    public Map<String, Object> deleteByCurrentUser(String appealNo, LoginUser user) {
        String phone = phoneOf(user);
        AppealParent appeal = appealParentMapper.selectByNo(appealNo);
        if (appeal == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "诉求不存在");
        }
        // 归属校验：仅本人可删除自己的诉求
        if (!phone.equals(appeal.getCitizenPhone())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权删除该诉求");
        }
        // 聚合该诉求全部工单（子诉求工单 + 旧数据直挂父诉求的工单）
        List<Map<String, Object>> children = new java.util.ArrayList<>(
                appealChildMapper.selectChildrenWithOrderByParentIds(List.of(appeal.getId())));
        if (children.isEmpty()) {
            org.tjetc.backend.entity.common.WorkOrder legacy =
                    adminWorkOrderMapper.selectBySourceAppealId(appeal.getId());
            if (legacy != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderId", legacy.getId());
                row.put("orderStatus", legacy.getStatus());
                row.put("disposeTime", legacy.getDisposeTime());
                row.put("closeTime", legacy.getCloseTime());
                row.put("evaluated", orderEvaluateMapper.selectByOrderId(legacy.getId()) != null);
                children.add(row);
            }
        }
        // 删除条件：全部工单均可删（已评价，或处置完成已超 7 天评价窗口）；无任何工单不可删
        boolean deletable = !children.isEmpty();
        for (Map<String, Object> c : children) {
            if (!rowDeletable(c)) {
                deletable = false;
                break;
            }
        }
        if (!deletable) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "诉求尚在办理或评价期内，评价后或街道办处置满7天（超评价窗口）才可删除");
        }
        // 软删除：父诉求 + 全部子诉求一并标记（保留历史，列表/查询统一过滤 is_deleted=0）
        appealParentMapper.deleteById(appeal.getId());
        appealChildMapper.deleteByParentId(appeal.getId());
        auditLogService.recordOperation("CITIZEN", "appeal-delete",
                "市民删除诉求（软删除，仅系统留档）：" + appeal.getAppealNo());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", true);
        return data;
    }

    /** 聚合行 → 该子诉求工单是否可随诉求删除（未生成工单不可删） */
    private boolean rowDeletable(Map<String, Object> c) {
        if (c.get("orderId") == null) {
            return false;
        }
        return orderDeletable((String) c.get("orderStatus"), toBool(c.get("evaluated")),
                toTime(c.get("disposeTime")), toTime(c.get("closeTime")));
    }

    /**
     * 工单是否可随诉求删除：街道办处置完成后（状态进入评价/审核环节），
     * 已评价；或未评价但处置完成已超 7 天评价窗口（与评价窗口起算一致：dispose_time，旧单回退 close_time）
     */
    private boolean orderDeletable(String orderStatus, boolean evaluated, LocalDateTime disposeTime, LocalDateTime closeTime) {
        if (orderStatus == null || !DISPOSED_ORDER_STATUS.contains(orderStatus)) {
            return false;
        }
        if (evaluated) {
            return true;
        }
        LocalDateTime windowBase = disposeTime != null ? disposeTime : closeTime;
        return windowBase != null && windowBase.plusDays(EVALUATE_WINDOW_DAYS).isBefore(LocalDateTime.now());
    }

    /** map 结果布尔值统一转换（MyBatis 可能映射为 Boolean 或 Number） */
    private boolean toBool(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof Number n) {
            return n.intValue() != 0;
        }
        return false;
    }

    /** map 结果时间统一转换（datetime 可能映射为 LocalDateTime 或 Timestamp） */
    private LocalDateTime toTime(Object raw) {
        if (raw instanceof LocalDateTime t) {
            return t;
        }
        if (raw instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        }
        return null;
    }
}