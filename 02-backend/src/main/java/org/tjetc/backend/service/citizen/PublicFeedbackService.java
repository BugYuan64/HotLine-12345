package org.tjetc.backend.service.citizen;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.citizen.EvaluateRequest;
import org.tjetc.backend.entity.citizen.AppealParent;
import org.tjetc.backend.entity.citizen.AppealChild;
import org.tjetc.backend.entity.citizen.OrderEvaluate;
import org.tjetc.backend.entity.common.WorkOrder;
import org.tjetc.backend.mapper.citizen.AppealChildMapper;
import org.tjetc.backend.mapper.citizen.AppealParentMapper;
import org.tjetc.backend.mapper.staff.OrderDisposeAttachmentMapper;
import org.tjetc.backend.mapper.citizen.OrderEvaluateMapper;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;
import org.tjetc.backend.mapper.common.WorkOrderAppealMapper;
import org.tjetc.backend.service.admin.FileService;
import org.tjetc.backend.service.admin.OrderLogService;
import org.tjetc.backend.service.admin.AuditLogService;
import org.tjetc.backend.service.common.OrderCopySyncService;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 市民工单反馈闭环（需求 24 / API 11.4）：
 * 处置结果查看 / 满意度评价（办结后7天，提交后不可修改）/ 重开申请（不满意可申请，最多1次）/ 流转时间线
 * 身份核验：诉求编号 + 预留手机号双重匹配，防止未授权查询（API 4.4 技术说明）
 */
@Service
@RequiredArgsConstructor
public class PublicFeedbackService {

    /** 办结后评价窗口天数（需求 24(2)：7天） */
    private static final int EVALUATE_WINDOW_DAYS = 7;

    private final AppealParentMapper appealParentMapper;
    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final WorkOrderAppealMapper workOrderAppealMapper;
    private final OrderEvaluateMapper evaluateMapper;
    private final OrderDisposeAttachmentMapper disposeAttachmentMapper;
    private final OrderLogService orderLogService;
    private final OrderCopySyncService orderCopySyncService;
    private final FileService fileService;
    private final AuditLogService auditLogService;
    private final AppealChildMapper appealChildMapper;

    /** 身份核验：诉求编号 + 手机号（登录市民可免传 phone，直接以登录账号手机号与预留手机号匹配） */
    private AppealParent verifyAppeal(String appealNo, String phone, LoginUser user) {
        // 校验Appeal
        AppealParent appeal = appealParentMapper.selectByNo(appealNo);
        if (appeal == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "诉求不存在");
        }
        // 登录态：以登录账号手机号为准（前端无需再传）；显式传 phone 时须与登录账号一致
        // 未登录：phone 必填（DTO 不再强制 NotBlank，由这里保证）
        String effective = (phone == null || phone.isBlank()) && user != null && user.phone() != null
                ? user.phone() : phone;
        if ((effective == null || effective.isBlank())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "请输入预留手机号");
        }
        if (user != null && user.phone() != null && !user.phone().equals(effective)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "手机号与登录账号不一致");
        }
        if (appeal.getCitizenPhone() == null || !appeal.getCitizenPhone().equals(effective)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "手机号与预留手机号不一致");
        }
        return appeal;
    }

    /** 诉求 → 工单查找（来源诉求关联；优先归并关联表，兼容阶段1无关联表的旧数据）；未生成工单返回 null */
    private WorkOrder findOrder(AppealParent appeal) {
        WorkOrder order = null;
        Long orderId = workOrderAppealMapper.selectOrderIdByAppealId(appeal.getId());
        if (orderId != null) {
            order = adminWorkOrderMapper.selectById(orderId);
        }
        if (order == null) {
            order = adminWorkOrderMapper.selectBySourceAppealId(appeal.getId());
        }
        return order;
    }

    /** 诉求 → 工单（未生成工单时抛业务异常，供需要工单的操作使用） */
    private WorkOrder orderOf(AppealParent appeal) {
        WorkOrder order = findOrder(appeal);
        if (order == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "该诉求尚未生成工单");
        }
        return order;
    }

    /**
     * 定位评价/重开目标工单：指定子诉求编号（拆分后单独评价某子诉求）则定位该子诉求的工单并校验归属；
     * 未指定则退回父诉求 legacy 工单（兼容未拆分旧单）
     */
    private WorkOrder resolveTargetOrder(AppealParent appeal, String childAppealNo) {
        if (childAppealNo != null && !childAppealNo.isBlank()) {
            AppealChild child = appealChildMapper.selectByNo(childAppealNo.trim());
            if (child == null || !appeal.getId().equals(child.getParentId())) {
                throw new BusinessException(ResultCode.DATA_NOT_FOUND, "子诉求不存在或不属于该诉求");
            }
            Long orderId = workOrderAppealMapper.selectOrderIdByAppealId(child.getId());
            WorkOrder order = orderId == null ? null : adminWorkOrderMapper.selectById(orderId);
            if (order == null) {
                throw new BusinessException(ResultCode.DATA_NOT_FOUND, "该子诉求尚未生成工单");
            }
            return order;
        }
        // 无子诉求定位：优先某一有工单的子诉求（父诉求本身无工单的拆分场景），再回退 legacy 直挂
        for (AppealChild child : appealChildMapper.selectChildrenByParentId(appeal.getId())) {
            Long orderId = workOrderAppealMapper.selectOrderIdByAppealId(child.getId());
            if (orderId != null) {
                WorkOrder o = adminWorkOrderMapper.selectById(orderId);
                if (o != null) {
                    return o;
                }
            }
        }
        return orderOf(appeal);
    }

    /**
     * 处置结果查看（API 11.4.1）：展示处置结果摘要/部门/街道办/完成时间/凭证（脱敏），
     * 不展示处置人员姓名与内部备注
     */
    public Map<String, Object> result(String appealNo, String childAppealNo, String phone, LoginUser user) {
        // 执行result操作
        AppealParent appeal = verifyAppeal(appealNo, phone, user);
        WorkOrder order = resolveTargetOrder(appeal, childAppealNo);
        // 街道办处置完成（含待评价/已评价/部门已审核/办结/归档）即可查看处置结果
        if (!"PENDING_EVALUATE".equals(order.getStatus()) && !"STREET_DONE".equals(order.getStatus())
                && !"EVALUATED".equals(order.getStatus()) && !"DEPT_AUDITED".equals(order.getStatus())
                && !"CLOSED".equals(order.getStatus()) && !"ARCHIVED".equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "工单尚未处置完成，暂无可查看的处置结果");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dispose_summary", order.getDisposeResult());
        data.put("dispose_plan", order.getDisposePlan());
        data.put("department", order.getDepartment());
        data.put("street_office", order.getStreetOffice());
        data.put("dispose_time", order.getDisposeTime());
        data.put("close_time", order.getCloseTime());
        data.put("dispose_attachments", disposeAttachmentMapper.selectByOrderId(order.getId()).stream()
                .map(a -> a.getFileName())
                .toList());
        // 凭证限时预签名直链（方案 B：文件在 MinIO，前端按链接打开/预览）
        data.put("dispose_attachment_urls", disposeAttachmentMapper.selectByOrderId(order.getId()).stream()
                .map(a -> fileService.presignedUrl(a.getFileUrl()))
                .toList());
        // 工单状态与是否已评价：前端据此控制评价表单显隐（处置完成后未评价可评）
        data.put("order_status", order.getStatus());
        data.put("evaluated", evaluateMapper.selectByOrderId(order.getId()) != null);
        return data;
    }

    /**
     * 诉求级流程进度（区别于工单时间线）：管理端处理中 → 部门端处理中(具体部门) → 街道办处理中(具体街道办) → 已办结。
     * 多子诉求多工单时取「最落后阶段」为当前进度（任一工单还在部门处理，整体即显示部门端处理中）。
     */
    public Map<String, Object> stage(String appealNo, String phone, LoginUser user) {
        AppealParent appeal = verifyAppeal(appealNo, phone, user);
        // 关联工单聚合：父 → 子诉求 → 工单；兼容旧数据 source_appeal_id 直挂
        List<WorkOrder> orders = new java.util.ArrayList<>();
        for (AppealChild child : appealChildMapper.selectChildrenByParentId(appeal.getId())) {
            Long orderId = workOrderAppealMapper.selectOrderIdByAppealId(child.getId());
            if (orderId == null) {
                continue;
            }
            WorkOrder o = adminWorkOrderMapper.selectById(orderId);
            if (o != null && orders.stream().noneMatch(x -> x.getId().equals(o.getId()))) {
                orders.add(o);
            }
        }
        WorkOrder legacy = findOrder(appeal);
        if (legacy != null && orders.stream().noneMatch(x -> x.getId().equals(legacy.getId()))) {
            orders.add(legacy);
        }

        // 阶段 index：0已提交 1管理端处理中 2部门端处理中 3街道办处理中 4已办结
        // 工单当前所处阶段（进行中）；无工单 → 管理端处理中
        int current = orders.isEmpty() ? 1 : orders.stream().mapToInt(this::orderStageIndex).min().orElse(1);
        String departments = orders.stream().map(WorkOrder::getDepartment)
                .filter(d -> d != null && !d.isBlank()).distinct().collect(java.util.stream.Collectors.joining("、"));
        String streetOffices = orders.stream().map(WorkOrder::getStreetOffice)
                .filter(s -> s != null && !s.isBlank()).distinct().collect(java.util.stream.Collectors.joining("、"));
        java.time.LocalDateTime closeTime = orders.stream().map(WorkOrder::getCloseTime)
                .filter(t -> t != null).max(java.time.LocalDateTime::compareTo).orElse(null);

        String[] labels = {"已提交", "管理端处理中", "部门端处理中", "街道办处理中", "已办结"};
        List<Map<String, Object>> stages = new java.util.ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("label", labels[i]);
            s.put("status", i < current ? "done" : (i == current ? "active" : "pending"));
            switch (i) {
                case 0 -> s.put("detail", "诉求已提交：" + appeal.getAppealNo());
                case 1 -> s.put("detail", orders.isEmpty() ? "诉求智能处理与分派" : "待分派至职能部门");
                case 2 -> s.put("detail", departments.isEmpty() ? "-" : "承办部门：" + departments);
                case 3 -> s.put("detail", streetOffices.isEmpty() ? "-" : "承办街道办：" + streetOffices);
                case 4 -> s.put("detail", closeTime == null ? "-" : "办结时间：" + closeTime.toString().replace('T', ' '));
                default -> s.put("detail", "-");
            }
            s.put("time", i == 0 ? appeal.getCreateTime() : (i == 4 ? closeTime : null));
            stages.add(s);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appeal_no", appeal.getAppealNo());
        data.put("title", appeal.getTitle());
        data.put("category", appeal.getCategory());
        data.put("current", current);
        data.put("stages", stages);
        return data;
    }

    /** 工单状态 → 诉求阶段 index（该工单当前所处阶段）
     *  阶段：0已提交 1管理端处理中 2部门端处理中 3街道办处理中 4已办结
     *  退回/驳回复工单仍属"处理中"：街道办驳回(STREET_REJECTED)回部门端处理；部门驳回(DEPT_REJECTED)回管理端终审 */
    private int orderStageIndex(WorkOrder o) {
        return switch (o.getStatus()) {
            case "PENDING_DISPATCH", "PENDING_REASSIGN", "DEPT_REJECTED" -> 1;
            case "PENDING_DEPT_PROCESS", "PENDING_DEPT_ASSIGN", "STREET_REJECTED", "PENDING_UPDATE" -> 2;
            case "PENDING_STREET_PROCESS" -> 3;
            default -> 4; // STREET_DONE / PENDING_EVALUATE / EVALUATED / DEPT_AUDITED / CLOSED / 归档 → 办结流程中或完成
        };
    }

    /**
     * 满意度评价（API 11.4.2）：街道办处置完成后7天内可评价一次（拆分后支持按子诉求单独评价），提交后不可修改；
     * 评价后工单进入 EVALUATED（已评价待部门审核）；超期未评价由部门审核时判定，自动标记未评价
     */
    @Transactional
    public Map<String, Object> evaluate(String appealNo, EvaluateRequest request, LoginUser user) {
        // 评价工单
        AppealParent appeal = verifyAppeal(appealNo, request.phone(), user);
        WorkOrder order = resolveTargetOrder(appeal, request.childAppealId());
        String before = order.getStatus();
        // 可评状态：待市民评价（新流程）/ 街道办已处理（存量）/ 已办结/已评价（历史兼容）
        if (!"PENDING_EVALUATE".equals(before) && !"STREET_DONE".equals(before)
                && !"CLOSED".equals(before) && !"EVALUATED".equals(before)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "工单尚未处置完成，暂不能评价");
        }
        OrderEvaluate exists = evaluateMapper.selectByOrderId(order.getId());
        if (exists != null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "已评价，评价提交后不可修改");
        }
        // 评价窗口起算：街道办处置完成时间（dispose_time）；旧单无处置时间时回退办结时间
        LocalDateTime windowBase = order.getDisposeTime() != null
                ? order.getDisposeTime() : order.getCloseTime();
        if (windowBase == null || windowBase.plusDays(EVALUATE_WINDOW_DAYS).isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "已超过处置后7天评价窗口，标记为未评价");
        }
        OrderEvaluate evaluate = new OrderEvaluate();
        evaluate.setOrderId(order.getId());
        // 登录态 phone 可空：落库取实际生效手机号（登录账号手机号，与 verifyAppeal 回退一致）
        String effectivePhone = (request.phone() == null || request.phone().isBlank())
                && user != null && user.phone() != null ? user.phone() : request.phone();
        evaluate.setAppealNo(request.childAppealId() != null && !request.childAppealId().isBlank()
                ? request.childAppealId().trim() : appealNo);
        evaluate.setCitizenPhone(effectivePhone);
        evaluate.setScore(request.score());
        evaluate.setLabel(request.label());
        evaluate.setRemark(request.remark());
        evaluate.setIsReopenApplied(0);
        evaluate.setEvaluateTime(LocalDateTime.now());
        evaluateMapper.insert(evaluate);
        // 评价后状态：待市民评价 → 已评价（EVALUATED，语义=已评价待部门审核）
        order.setStatus("EVALUATED");
        orderCopySyncService.sync(order);
        orderLogService.record(order.getId(), "EVALUATE", null, "CITIZEN",
                "市民评价：" + request.score() + "星（" + request.label() + "）", before, "EVALUATED");
        // 系统审计：收到市民评价 → 归入部门端审计日志（target_type 与「收到工单」同口径：部门代号[:区县编码]，
        // 保证部门端/管理端「部门审计」按 56 部门口径能检索到本条记录）
        auditLogService.recordOperation("CITIZEN", "appeal-evaluate",
                "收到市民评价工单 " + order.getOrderNo() + "：" + request.score() + "星（" + request.label() + "）",
                order.getCountyCode() == null || order.getCountyCode().isBlank()
                        ? order.getCategory() : order.getCategory() + ":" + order.getCountyCode());
        return Map.of("evaluated", true);
    }

    /**
     * 重开申请（API 11.4.3）：评价为不满意/非常不满意可申请，需补充说明≤500字，最多重开1次
     */
    public Map<String, Object> reopen(String appealNo, String childAppealNo, String phone, String reopenReason, LoginUser user) {
        // 重开工单
        AppealParent appeal = verifyAppeal(appealNo, phone, user);
        WorkOrder order = resolveTargetOrder(appeal, childAppealNo);
        OrderEvaluate evaluate = evaluateMapper.selectByOrderId(order.getId());
        if (evaluate == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请先完成满意度评价");
        }
        if (!"DISSATISFIED".equals(evaluate.getLabel()) && !"VERY_DISSATISFIED".equals(evaluate.getLabel())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅不满意/非常不满意评价可申请重开");
        }
        if (evaluate.getIsReopenApplied() != null && evaluate.getIsReopenApplied() == 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "已提交重开申请，请勿重复提交");
        }
        int reopenCount = order.getReopenCount() == null ? 0 : order.getReopenCount();
        if (reopenCount >= 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "工单重开次数已达上限（最多1次）");
        }
        evaluate.setIsReopenApplied(1);
        evaluate.setReopenReason(reopenReason);
        evaluate.setReopenStatus("PENDING");
        evaluateMapper.updateReopenApply(evaluate);
        return Map.of("reopen_status", "PENDING");
    }

    /**
     * 工单流转时间线（API 11.4.4）：节点显示时间与操作方角色（不暴露具体人员姓名）。
     * 拆分后按「父诉求 + 各子诉求」分组返回，每组展示该子诉求关联工单的实时时间线 + 状态/是否已评价
     */
    public Map<String, Object> timeline(String appealNo, String phone, LoginUser user) {
        // 执行timeline操作
        AppealParent appeal = verifyAppeal(appealNo, phone, user);
        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<Map<String, Object>> groups = new java.util.ArrayList<>();

        // 父诉求组：提交节点（order_log 覆盖工单生命周期，诉求提交节点由诉求记录补充）
        Map<String, Object> parentGroup = new LinkedHashMap<>();
        parentGroup.put("scope", "PARENT");
        parentGroup.put("appeal_no", appeal.getAppealNo());
        parentGroup.put("title", appeal.getTitle());
        parentGroup.put("status", appeal.getStatus());
        parentGroup.put("node", "已提交");
        parentGroup.put("time", appeal.getCreateTime() == null ? "" : appeal.getCreateTime().format(fmt));
        parentGroup.put("role", "市民");
        parentGroup.put("content", "提交诉求");
        groups.add(parentGroup);

        // 子诉求组：逐个子诉求独立展示其工单时间线（无工单显示 AI 处理中）
        List<AppealChild> children = appealChildMapper.selectChildrenByParentId(appeal.getId());
        if (children.isEmpty()) {
            // 兼容旧数据直挂（父诉求 source_appeal_id 关联工单）；已评价标记与子诉求组同口径计算
            WorkOrder legacyOrder = findOrder(appeal);
            addOrderGroup(groups, appeal.getTitle(), appeal.getAppealNo(), "PENDING",
                    legacyOrder == null ? null : evaluateMapper.selectByOrderId(legacyOrder.getId()) != null, legacyOrder, fmt);
        } else {
            for (AppealChild child : children) {
                Long orderId = workOrderAppealMapper.selectOrderIdByAppealId(child.getId());
                WorkOrder order = orderId == null ? null : adminWorkOrderMapper.selectById(orderId);
                addOrderGroup(groups, child.getTitle(), child.getAppealNo(), child.getStatus(),
                        evaluateMapper.selectByOrderId(order == null ? -1L : order.getId()) != null, order, fmt);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appeal_no", appeal.getAppealNo());
        data.put("title", appeal.getTitle());
        data.put("groups", groups);
        // 诉求信息（办理进度查询页/进度弹窗顶部展示：当时填写的内容、地址、类别与附件）
        Map<String, Object> appealInfo = new LinkedHashMap<>();
        appealInfo.put("appeal_no", appeal.getAppealNo());
        appealInfo.put("title", appeal.getTitle());
        appealInfo.put("content", appeal.getContent());
        appealInfo.put("category", appeal.getCategory());
        appealInfo.put("create_time", appeal.getCreateTime() == null ? "" : appeal.getCreateTime().format(fmt));
        // 地址：宽泛诉求（全市范围）直接展示；否则取提交时的完整地址
        appealInfo.put("region", appeal.getBroadFlag() != null && appeal.getBroadFlag() == 1
                ? "全市范围" : appeal.getRegion());
        // 诉求附件（市民提交时上传，MinIO 内容端点可访问）
        appealInfo.put("attachments", fileService.listByAppeal(appeal.getId()));
        data.put("appeal", appealInfo);
        return data;
    }

    /** 构造一组时间线：有工单则其 order_log 实时流转；无工单显示 AI 处理中占位 */
    private void addOrderGroup(List<Map<String, Object>> groups, String title, String appealNo,
                               String status, Boolean evaluated, WorkOrder order,
                               java.time.format.DateTimeFormatter fmt) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("scope", "CHILD");
        group.put("appeal_no", appealNo);
        group.put("title", title == null || title.isBlank() ? appealNo : title);
        group.put("status", status);
        // 工单实时状态：前端据此判断该子诉求是否已完成处置（可评价）
        group.put("order_status", order == null ? null : order.getStatus());
        if (evaluated != null) {
            group.put("evaluated", evaluated);
        }
        // 评价详情：已评价时返回等级/评分/重开状态，前端据此展示「已评价」与重开申请入口
        if (evaluated != null && evaluated && order != null) {
            OrderEvaluate ev = evaluateMapper.selectByOrderId(order.getId());
            if (ev != null) {
                Map<String, Object> evm = new LinkedHashMap<>();
                evm.put("score", ev.getScore());
                evm.put("label", ev.getLabel());
                // 文字说明与评价时间：市民端评价区域「查看评价内容」展示
                evm.put("remark", ev.getRemark());
                evm.put("reopen_status", ev.getReopenStatus());
                evm.put("evaluate_time", ev.getEvaluateTime() == null ? "" : ev.getEvaluateTime().format(fmt));
                group.put("evaluate", evm);
            }
        }
        List<Map<String, Object>> timeline = new java.util.ArrayList<>();
        if (order != null) {
            List<Map<String, Object>> logs = orderLogService.timeline(order.getId());
            if (logs != null && !logs.isEmpty()) {
                timeline.addAll(logs);
            } else {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("node", "AI处理中");
                pending.put("time", "");
                pending.put("role", "系统");
                pending.put("content", "诉求正在智能拆分与分类，尚未生成工单");
                timeline.add(pending);
            }
        } else {
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("node", "AI处理中");
            pending.put("time", "");
            pending.put("role", "系统");
            pending.put("content", "诉求正在智能拆分与分类，尚未生成工单");
            timeline.add(pending);
        }
        group.put("timeline", timeline);
        groups.add(group);
    }
}