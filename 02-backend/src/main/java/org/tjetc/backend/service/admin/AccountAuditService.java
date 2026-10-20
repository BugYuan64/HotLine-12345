package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.util.MaskUtil;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.AccountAuditBatchRequest;
import org.tjetc.backend.entity.admin.SysAccountAudit;
import org.tjetc.backend.mapper.admin.SysAccountAuditMapper;
import org.tjetc.backend.mapper.common.SysUserMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号审核管理（需求 4 账号审核 / API 5.3/5.4/5.5/5.7）：
 * - 自助注册申请审核列表（按状态/姓名/工号筛选，提交时间倒序）
 * - 审核通过：用户 audit_status 置 APPROVED，账号即刻生效可登录
 * - 审核驳回：填写驳回原因，用户 audit_status 置 REJECTED，登录时展示驳回原因
 * - 批量通过/驳回，返回成功与失败条数，全程审计留痕
 */
@RequiredArgsConstructor
@Service
public class AccountAuditService {

    private final SysAccountAuditMapper auditMapper;
    private final SysUserMapper userMapper;
    private final AuditLogService auditLogService;

    /** 审核列表（API 5.3） */
    public PageData<Map<String, Object>> page(String status, String name, String jobNo,
                                              int page, int pageSize) {
        // 分页查询
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> rows = auditMapper.selectPage(status, name, jobNo, offset, pageSize);
        long total = auditMapper.countPage(status, name, jobNo);
        List<Map<String, Object>> list = rows.stream().map(this::toVo).toList();
        return PageData.of(list, total, page, pageSize);
    }

    /** 审核通过（API 5.4） */
    @Transactional
    public Map<String, Object> approve(Long id, LoginUser user) {
        // 审批通过
        SysAccountAudit audit = requirePending(id);
        audit.setAuditStatus("APPROVED");
        audit.setRejectReason(null);
        audit.setAuditUserId(user.userId());
        audit.setAuditTime(LocalDateTime.now());
        auditMapper.updateAudit(audit);
        userMapper.updateAuditStatus(audit.getUserId(), "APPROVED");
        userMapper.updateStatus(audit.getUserId(), "ENABLED");
        auditLogService.recordOperation("ADMIN-" + user.jobNo(), "account-audit",
                "账号审核通过：审核记录ID " + id + "，申请人ID " + audit.getUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("audit_status", "APPROVED");
        return data;
    }

    /** 审核驳回（API 5.5） */
    @Transactional
    public Map<String, Object> reject(Long id, String reason, LoginUser user) {
        // 驳回操作
        SysAccountAudit audit = requirePending(id);
        audit.setAuditStatus("REJECTED");
        audit.setRejectReason(reason);
        audit.setAuditUserId(user.userId());
        audit.setAuditTime(LocalDateTime.now());
        auditMapper.updateAudit(audit);
        userMapper.updateAuditStatus(audit.getUserId(), "REJECTED");
        auditLogService.recordOperation("ADMIN-" + user.jobNo(), "account-audit",
                "账号审核驳回：审核记录ID " + id + "，驳回原因 " + reason);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("audit_status", "REJECTED");
        return data;
    }

    /** 账号批量审核（API 5.7）：逐条处理，已处理的跳过计为失败 */
    @Transactional
    public Map<String, Object> batch(AccountAuditBatchRequest req, LoginUser user) {
        // 批量
        if ("REJECT".equals(req.action()) && (req.rejectReason() == null || req.rejectReason().isBlank())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "批量驳回时必须填写驳回原因");
        }
        int success = 0;
        int fail = 0;
        for (Long id : req.auditIds()) {
            SysAccountAudit audit = auditMapper.selectById(id);
            if (audit == null || !"PENDING".equals(audit.getAuditStatus())) {
                fail++;
                continue;
            }
            audit.setAuditUserId(user.userId());
            audit.setAuditTime(LocalDateTime.now());
            if ("APPROVE".equals(req.action())) {
                audit.setAuditStatus("APPROVED");
                audit.setRejectReason(null);
            } else {
                audit.setAuditStatus("REJECTED");
                audit.setRejectReason(req.rejectReason());
            }
            auditMapper.updateAudit(audit);
            userMapper.updateAuditStatus(audit.getUserId(), audit.getAuditStatus());
            if ("APPROVE".equals(req.action())) {
                userMapper.updateStatus(audit.getUserId(), "ENABLED");
            }
            success++;
        }
        auditLogService.recordOperation("ADMIN-" + user.jobNo(), "account-audit",
                "账号批量审核：" + req.action() + "，成功 " + success + " 条，失败 " + fail + " 条");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success_count", success);
        data.put("fail_count", fail);
        return data;
    }

    private SysAccountAudit requirePending(Long id) {
        // 执行requirePending操作
        SysAccountAudit audit = auditMapper.selectById(id);
        if (audit == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "审核记录不存在");
        }
        if (!"PENDING".equals(audit.getAuditStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该申请已审核，请勿重复操作");
        }
        return audit;
    }

    private Map<String, Object> toVo(Map<String, Object> r) {
        // 执行toVo操作
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("applicant_name", r.get("applicant_name"));
        m.put("job_no", r.get("job_no"));
        m.put("company", r.get("company"));
        m.put("user_type", r.get("user_type"));
        m.put("region", r.get("region"));
        m.put("phone", MaskUtil.maskPhone((String) r.get("phone")));
        m.put("audit_status", r.get("audit_status"));
        m.put("reject_reason", r.get("reject_reason"));
        m.put("apply_time", r.get("apply_time"));
        m.put("audit_time", r.get("audit_time"));
        return m;
    }
}