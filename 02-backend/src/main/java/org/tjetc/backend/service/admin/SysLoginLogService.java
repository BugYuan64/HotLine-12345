package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.entity.admin.SysLoginLog;
import org.tjetc.backend.mapper.admin.SysLoginLogMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录日志（需求 3(13) 登录日志 / 18(4) 登录日志查看 / API 9.11）
 */
@Service
@RequiredArgsConstructor
public class SysLoginLogService {

    private final SysLoginLogMapper loginLogMapper;

    public void record(Long userId, String loginMethod, String ip, String device, String result) {
        // 执行record操作
        SysLoginLog log = new SysLoginLog();
        log.setUserId(userId);
        log.setLoginMethod(loginMethod);
        log.setIp(ip);
        log.setDevice(device != null && device.length() > 200 ? device.substring(0, 200) : device);
        log.setLoginTime(LocalDateTime.now());
        log.setResult(result);
        loginLogMapper.insert(log);
    }

    /**
     * 个人登录日志分页查询（API 9.11 响应字段：id/login_method/ip/device/login_time/result）
     */
    public PageData<Map<String, Object>> pageByUserId(Long userId, int page, int pageSize) {
        // 执行pageByUserId操作
        int offset = Math.max(0, (page - 1) * pageSize);
        List<SysLoginLog> logs = loginLogMapper.selectByUserId(userId, offset, pageSize);
        long total = loginLogMapper.countByUserId(userId);
        List<Map<String, Object>> list = logs.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("login_method", l.getLoginMethod());
            m.put("ip", l.getIp());
            m.put("device", l.getDevice());
            m.put("login_time", l.getLoginTime() == null ? null : l.getLoginTime().toString());
            m.put("result", l.getResult());
            return m;
        }).toList();
        return PageData.of(list, total, page, pageSize);
    }
}