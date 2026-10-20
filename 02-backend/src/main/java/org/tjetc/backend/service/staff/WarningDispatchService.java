package org.tjetc.backend.service.staff;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.staff.WarningDispatchRequest;
import org.tjetc.backend.entity.admin.WarningRecord;
import org.tjetc.backend.entity.admin.WarningStreetDispatch;
import org.tjetc.backend.entity.common.SysMessage;
import org.tjetc.backend.entity.common.SysRegion;
import org.tjetc.backend.entity.common.SysUser;
import org.tjetc.backend.mapper.admin.WarningRecordMapper;
import org.tjetc.backend.mapper.admin.WarningStreetDispatchMapper;
import org.tjetc.backend.mapper.common.SysMessageMapper;
import org.tjetc.backend.mapper.common.SysUserMapper;
import org.tjetc.backend.service.admin.AuditLogService;
import org.tjetc.backend.service.common.RegionService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 预警分发到街道办（部门端发起、管理端只读进度）：
 * - 分发对象为「街道/乡镇区域」（sys_region 乡镇级），街道办账号按辖区 region 自动可见
 * - 预警辖区为空时不圈定区县，列出全市街道供部门自选
 * - 分发后向对应街道办的启用账号推送站内消息，全程审计留痕
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarningDispatchService {

    private final WarningRecordMapper recordMapper;
    private final WarningStreetDispatchMapper dispatchMapper;
    private final RegionService regionService;
    private final SysUserMapper userMapper;
    private final SysMessageMapper messageMapper;
    private final AuditLogService auditLogService;

    /**
     * 可分发街道候选（按区县分组）：预警辖区非空则仅列该区县下乡镇，为空则列全市乡镇；
     * 每条街道附是否有启用账号、是否已分发、分发后的响应状态
     */
    public List<Map<String, Object>> candidates(Long warningId, LoginUser user) {
        WarningRecord record = requireDispatchable(warningId, user);
        Map<String, WarningStreetDispatch> dispatched = dispatchMapper.selectByWarningId(warningId).stream()
                .collect(Collectors.toMap(WarningStreetDispatch::getStreetOffice, d -> d, (a, b) -> a));
        Set<String> accountRegions = userMapper.selectEnabledByUserType("STREET").stream()
                .map(SysUser::getRegion)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        String targetCounty = (record.getCountyName() == null || record.getCountyName().isBlank())
                ? null : regionService.resolveCountyCode(record.getCountyName());
        List<Map<String, Object>> groups = new ArrayList<>();
        for (SysRegion county : regionService.listCounties()) {
            if (targetCounty != null && !targetCounty.equals(county.getRegionCode())) {
                continue;
            }
            List<Map<String, Object>> streets = new ArrayList<>();
            for (SysRegion child : regionService.listChildren(county.getRegionCode())) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("street_code", child.getRegionCode());
                s.put("street_name", child.getRegionName());
                s.put("has_account", accountRegions.contains(child.getRegionName()));
                WarningStreetDispatch d = dispatched.get(child.getRegionName());
                s.put("dispatched", d != null);
                s.put("respond_status", d == null ? null : d.getRespondStatus());
                streets.add(s);
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("county_code", county.getRegionCode());
            g.put("county_name", county.getRegionName());
            g.put("streets", streets);
            groups.add(g);
        }
        return groups;
    }

    /**
     * 执行分发：校验街道名合法性（在候选范围内），跳过已分发的街道，
     * 落库分发关系 → 推送对应街道办账号站内消息 → 审计留痕
     */
    public Map<String, Object> dispatch(Long warningId, WarningDispatchRequest request, LoginUser user) {
        WarningRecord record = requireDispatchable(warningId, user);
        Map<String, String[]> streetMap = buildStreetMap(record.getCountyName());
        Set<String> dispatched = dispatchMapper.selectByWarningId(warningId).stream()
                .map(WarningStreetDispatch::getStreetOffice)
                .collect(Collectors.toSet());

        List<WarningStreetDispatch> toInsert = new ArrayList<>();
        for (String name : request.streetOffices()) {
            String n = name == null ? "" : name.trim();
            if (n.isEmpty()) {
                continue;
            }
            String[] info = streetMap.get(n);
            if (info == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "非法街道名称：" + n);
            }
            if (dispatched.contains(n)) {
                continue; // 已分发的跳过，不重复落库
            }
            WarningStreetDispatch d = new WarningStreetDispatch();
            d.setWarningId(warningId);
            d.setStreetCode(info[0]);
            d.setStreetOffice(n);
            d.setCountyName(info[1]);
            d.setRespondStatus("UNRESPONDED");
            d.setDispatchTime(LocalDateTime.now());
            toInsert.add(d);
        }
        if (toInsert.isEmpty()) {
            return Map.of("dispatched", 0, "message_pushed", 0);
        }
        dispatchMapper.insertBatch(toInsert);

        // 站内消息：推送给这些街道的启用账号（无账号不推送，账号注册后经分发关系自动可见）
        Set<String> names = toInsert.stream().map(WarningStreetDispatch::getStreetOffice).collect(Collectors.toSet());
        int pushed = 0;
        for (SysUser u : userMapper.selectEnabledByUserType("STREET")) {
            if (u.getRegion() == null || !names.contains(u.getRegion())) {
                continue;
            }
            SysMessage message = new SysMessage();
            message.setUserId(u.getId());
            message.setMsgType("WARNING");
            message.setTitle("【预警分发】" + record.getWarningNo());
            message.setContent("预警 " + record.getWarningNo() + "（"
                    + (record.getRegion() == null || record.getRegion().isBlank() ? "全市" : record.getRegion())
                    + "）已分发至你辖区，请及时查看并标记响应。");
            message.setIsRead(0);
            message.setChannel("IN_APP");
            messageMapper.insert(message);
            pushed++;
        }

        String operator = user.jobNo() == null || user.jobNo().isBlank()
                ? user.userType() : user.userType() + "-" + user.jobNo();
        auditLogService.recordOperation(operator, "warning-dispatch",
                "分发预警 " + record.getWarningNo() + " → " + String.join("、", names));
        log.info("预警 {} 分发街道办 {} 个，站内消息推送 {} 条（操作人 {}）",
                record.getWarningNo(), toInsert.size(), pushed, operator);
        return Map.of("dispatched", toInsert.size(), "message_pushed", pushed);
    }

    /** 部门端查看分发进度（校验归属后只读） */
    public List<Map<String, Object>> staffProgress(Long warningId, LoginUser user) {
        requireDispatchable(warningId, user);
        return progress(warningId);
    }

    /** 管理端查看分发进度（只读） */
    public List<Map<String, Object>> progress(Long warningId) {
        if (recordMapper.selectById(warningId) == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "预警记录不存在");
        }
        return dispatchMapper.selectByWarningId(warningId).stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("county_name", d.getCountyName());
            m.put("street_code", d.getStreetCode());
            m.put("street_office", d.getStreetOffice());
            m.put("respond_status", d.getRespondStatus());
            m.put("dispatch_time", d.getDispatchTime());
            m.put("respond_time", d.getRespondTime());
            m.put("respond_user_name", d.getRespondUserName());
            return m;
        }).toList();
    }

    /** 分发前置校验：仅本部门（category=user_type）的已发送预警可分发 */
    private WarningRecord requireDispatchable(Long warningId, LoginUser user) {
        WarningRecord record = recordMapper.selectById(warningId);
        if (record == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "预警记录不存在");
        }
        if (!"SENT".equals(record.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅已发送预警可分发给街道办");
        }
        if (record.getCategory() != null && !record.getCategory().equals(user.userType())) {
            throw new BusinessException(ResultCode.REGION_FORBIDDEN, "越权分发其他部门预警");
        }
        return record;
    }

    /** 构建街道名 -> [乡镇编码, 所属区县名] 映射：预警辖区非空圈定该区县，为空覆盖全市 */
    private Map<String, String[]> buildStreetMap(String countyName) {
        String countyCode = (countyName == null || countyName.isBlank())
                ? null : regionService.resolveCountyCode(countyName);
        Map<String, String[]> map = new LinkedHashMap<>();
        for (SysRegion county : regionService.listCounties()) {
            if (countyCode != null && !countyCode.equals(county.getRegionCode())) {
                continue;
            }
            for (SysRegion child : regionService.listChildren(county.getRegionCode())) {
                map.put(child.getRegionName(), new String[]{child.getRegionCode(), county.getRegionName()});
            }
        }
        return map;
    }
}