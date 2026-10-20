package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.citizen.AppealSubmitRequest;
import org.tjetc.backend.entity.admin.SensitiveEvent;
import org.tjetc.backend.entity.common.SysFile;
import org.tjetc.backend.entity.common.SysMessage;
import org.tjetc.backend.entity.common.SysUser;
import org.tjetc.backend.mapper.admin.SensitiveEventMapper;
import org.tjetc.backend.mapper.common.SysFileMapper;
import org.tjetc.backend.mapper.common.SysMessageMapper;
import org.tjetc.backend.mapper.common.SysUserMapper;
import org.tjetc.backend.service.citizen.PublicAppealService;
import org.tjetc.backend.service.common.AiServiceClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 敏感/紧急事件感知与查询（需求 9(9) / API 10.7）：
 * 诉求文本语义强度检测，风险命中即入库告警并站内推送；管理端分页查询与标记处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveEventService {

    private final SensitiveEventMapper sensitiveEventMapper;
    private final SysMessageMapper messageMapper;
    private final SysUserMapper userMapper;
    private final AiServiceClient aiServiceClient;
    private final AuditLogService auditLogService;
    private final SysFileMapper sysFileMapper;
    private final FileService fileService;

    private static final DateTimeFormatter NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 提交前置敏感检测（全部剥离模式）：
     * 市民提交诉求时同步调用 AI 检测，命中则将诉求快照完整剥离入 sensitive_event
     * （不生成父诉求/不拆分/不建单），并推送 ADMIN 站内告警；
     * 未命中或 AI 不可用返回 empty（降级不阻断业务，走正常诉求流程）
     */
    public Optional<SensitiveEvent> detectAtSubmit(AppealSubmitRequest request, String citizenPhone) {
        // 提交前置敏感检测
        return aiServiceClient.sensitive(null, request.content()).flatMap(result -> {
            if ("none".equals(result.riskType())) {
                return Optional.empty();
            }
            SensitiveEvent event = new SensitiveEvent();
            event.setAppealNo(nextSensitiveNo());
            event.setRiskType(result.riskType());
            event.setRiskLevel(result.riskLevel());
            event.setHandleStatus("UNHANDLED");
            event.setPushTargets("ADMIN");
            event.setAdvice(result.advice());
            // 诉求快照：剥离模式下诉求不进入父诉求域，快照字段即唯一数据来源
            event.setContent(request.content());
            event.setCategory(request.category());
            event.setCitizenPhone(citizenPhone);
            event.setProvince(request.province());
            event.setCity(request.city());
            event.setDistrict(request.district());
            event.setStreet(request.street());
            event.setCommunity(request.community());
            event.setAddressDetail(request.addressDetail());
            event.setRegion(PublicAppealService.joinRegion(request.district(), request.street(), request.addressDetail()));
            event.setBroadFlag(Boolean.TRUE.equals(request.broadFlag()) ? 1 : 0);
            // 剥离模式下附件不绑定父诉求（无 appeal_id 可挂），改存文件ID列表供管理端详情展示
            if (request.attachmentIds() != null && !request.attachmentIds().isEmpty()) {
                event.setAttachmentIds(request.attachmentIds().stream()
                        .filter(id -> id != null)
                        .map(String::valueOf)
                        .reduce((a, b) -> a + "," + b)
                        .orElse(null));
            }
            sensitiveEventMapper.insert(event);
            pushSensitiveAlert(event, result.advice());
            auditLogService.recordAiGeneration("SENSITIVE",
                    "AI生成·敏感事件感知：" + result.riskType() + "（" + result.riskLevel() + "）",
                    null, result.advice(), "qwen-plus");
            log.warn("敏感事件触发（提交剥离） appealNo={} riskType={} level={}", event.getAppealNo(), result.riskType(), result.riskLevel());
            return Optional.of(event);
        });
    }

    /** 敏感事件编号：SE + 时间戳 + 序号（如 SE20260908143012 + 3位序号），表内唯一 */
    private String nextSensitiveNo() {
        String prefix = "SE" + LocalDateTime.now().format(NO_TS);
        long seq = sensitiveEventMapper.countByPrefix(prefix) + 1;
        return prefix + String.format("%03d", seq);
    }

    /**
     * 敏感/紧急事件实时感知（需求 9(9) / API 10.7）：
     * 诉求文本语义强度检测，风险命中即入库告警；AI 不可用返回 false（降级不阻断业务）
     */
    public boolean detectSensitive(Long appealId, String text) {
        // 检测Sensitive
        return aiServiceClient.sensitive(appealId, text).map(result -> {
            if (!"none".equals(result.riskType())) {
                SensitiveEvent event = new SensitiveEvent();
                event.setAppealId(appealId);
                event.setRiskType(result.riskType());
                event.setRiskLevel(result.riskLevel());
                event.setHandleStatus("UNHANDLED");
                event.setPushTargets("ADMIN");
                sensitiveEventMapper.insert(event);
                // B1：敏感事件命中后追加向 ADMIN 站内推送（只增不改，原业务不受影响）
                pushSensitiveAlert(event, result.advice());
                auditLogService.recordAiGeneration("SENSITIVE",
                        "AI生成·敏感事件感知：" + result.riskType() + "（" + result.riskLevel() + "）",
                        null, result.advice(), "qwen-plus");
                log.warn("敏感事件触发 appealId={} riskType={} level={}", appealId, result.riskType(), result.riskLevel());
                return true;
            }
            return false;
        }).orElse(false);
    }

    /** 敏感事件命中后追加向 ADMIN 站内推送（B1），复用消息表，不阻断原业务 */
    private void pushSensitiveAlert(SensitiveEvent event, String advice) {
        // 推送敏感事件Alert
        List<SysUser> admins = userMapper.selectEnabledByUserType("ADMIN");
        String content = "风险类型：" + event.getRiskType() + "；风险等级：" + event.getRiskLevel()
                + "；来源诉求ID：" + event.getAppealId()
                + (advice == null || advice.isBlank() ? "" : "；处置建议：" + advice)
                + "。请及时关注处理。";
        for (SysUser user : admins) {
            SysMessage message = new SysMessage();
            message.setUserId(user.getId());
            message.setMsgType("WARNING");
            message.setTitle("【敏感事件告警】" + event.getRiskType());
            message.setContent(content);
            message.setIsRead(0);
            message.setChannel("IN_APP");
            messageMapper.insert(message);
        }
        log.info("敏感事件 {} 站内消息推送 {} 人", event.getRiskType(), admins.size());
    }

    /** 敏感事件分页查询（风险等级/处理状态筛选）：返回全量快照字段 + 附件明细，供管理端详情对话框展示 */
    public PageData<Map<String, Object>> pageSensitive(String riskLevel, String handleStatus,
                                                       int page, int pageSize) {
        // 分页查询敏感事件
        int offset = Math.max(0, (page - 1) * pageSize);
        List<SensitiveEvent> rows = sensitiveEventMapper.selectPage(riskLevel, handleStatus, offset, pageSize);
        long total = sensitiveEventMapper.countPage(riskLevel, handleStatus);
        List<Map<String, Object>> list = rows.stream().map(e -> {
            Map<String, Object> m = toFullMap(e);
            m.put("attachments", resolveAttachments(e.getAttachmentIds()));
            return m;
        }).toList();
        return PageData.of(list, total, page, pageSize);
    }

    /** 敏感事件全量字段快照（管理端"查看"对话框数据源） */
    private Map<String, Object> toFullMap(SensitiveEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("appeal_id", e.getAppealId());
        m.put("appeal_no", e.getAppealNo());
        m.put("risk_type", e.getRiskType());
        m.put("risk_level", e.getRiskLevel());
        m.put("trigger_time", e.getTriggerTime());
        m.put("handle_status", e.getHandleStatus());
        m.put("push_targets", e.getPushTargets());
        m.put("content", e.getContent());
        m.put("category", e.getCategory());
        m.put("citizen_name", e.getCitizenName());
        m.put("citizen_phone", e.getCitizenPhone());
        m.put("province", e.getProvince());
        m.put("city", e.getCity());
        m.put("district", e.getDistrict());
        m.put("street", e.getStreet());
        m.put("community", e.getCommunity());
        m.put("address_detail", e.getAddressDetail());
        m.put("region", e.getRegion());
        m.put("broad_flag", e.getBroadFlag());
        m.put("advice", e.getAdvice());
        m.put("attachment_ids", e.getAttachmentIds());
        return m;
    }

    /** 解析附件ID列表（逗号分隔）为文件明细：[{file_name, file_url, file_size, file_type}]
     *  file_url 经 MinIO 同源转换（/minio/{bucket}/{objectKey}），前端 el-link 直接可打开 */
    private List<Map<String, Object>> resolveAttachments(String attachmentIds) {
        List<Map<String, Object>> files = new ArrayList<>();
        if (attachmentIds == null || attachmentIds.isBlank()) {
            return files;
        }
        for (String part : attachmentIds.split(",")) {
            String idStr = part.trim();
            if (idStr.isEmpty()) {
                continue;
            }
            try {
                SysFile file = sysFileMapper.selectById(Long.parseLong(idStr));
                if (file != null) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("file_name", file.getFileName());
                    f.put("file_url", fileService.presignedUrl(file.getFileUrl()));
                    f.put("file_size", file.getFileSize());
                    f.put("file_type", file.getFileType());
                    files.add(f);
                }
            } catch (NumberFormatException ex) {
                log.warn("敏感事件附件ID非法：{}", idStr);
            }
        }
        return files;
    }

    /**
     * 市民端"办理进度"：查询当前账号的敏感事件（剥离模式诉求快照），
     * 仅返回市民可见字段（编号/内容/提交时间/处理状态），不暴露风险类型与处置建议
     */
    public PageData<Map<String, Object>> pageByCitizenPhone(String citizenPhone, int page, int pageSize, String appealNo) {
        int offset = Math.max(0, (page - 1) * pageSize);
        List<SensitiveEvent> rows = sensitiveEventMapper.selectPageByPhone(citizenPhone, appealNo, offset, pageSize);
        long total = sensitiveEventMapper.countPageByPhone(citizenPhone, appealNo);
        List<Map<String, Object>> list = rows.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("appeal_no", e.getAppealNo());
            m.put("content", e.getContent());
            m.put("district", e.getDistrict());
            m.put("street", e.getStreet());
            m.put("address_detail", e.getAddressDetail());
            m.put("trigger_time", e.getTriggerTime());
            m.put("handle_status", e.getHandleStatus());
            return m;
        }).toList();
        return PageData.of(list, total, page, pageSize);
    }

    /** 标记敏感事件已处理 */
    public Map<String, Object> handleSensitive(Long id) {
        // 标记敏感事件已处理
        if (sensitiveEventMapper.updateHandleStatus(id, "HANDLED") == 0) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "敏感事件不存在");
        }
        return Map.of("handled", true);
    }
}