package org.tjetc.backend.service.citizen;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.citizen.MessageVO;
import org.tjetc.backend.dto.citizen.NotificationSettingsRequest;
import org.tjetc.backend.entity.common.NotificationSettings;
import org.tjetc.backend.mapper.common.NotificationSettingsMapper;
import org.tjetc.backend.mapper.common.SysMessageMapper;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公众端消息通知服务（API P-N-01~03）：
 * 消息列表 / 标记已读 / 通知偏好设置
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    private final SysMessageMapper messageMapper;
    private final NotificationSettingsMapper settingsMapper;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String phoneOf(LoginUser user) {
        // 获取用户手机号
        if (user == null || user.phone() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        return user.phone();
    }

    /** 消息列表（API P-N-01）：分页 + 未读数 */
    public Map<String, Object> list(LoginUser user, int page, int pageSize) {
        // 查询列表数据
        String phone = phoneOf(user);
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> rows = messageMapper.selectByPhonePage(phone, offset, pageSize);
        long total = messageMapper.countByPhone(phone);
        long unread = messageMapper.countUnread(phone);

        List<MessageVO> messages = rows.stream().map(row -> {
            MessageVO vo = new MessageVO();
            vo.setId(row.get("id") != null ? ((Number) row.get("id")).longValue() : null);
            vo.setMsgType(v(row, "msgType"));
            vo.setTitle(v(row, "title"));
            vo.setContent(v(row, "content"));
            Object isRead = row.get("isRead");
            vo.setIsRead(isRead != null ? ((Number) isRead).intValue() : 0);
            vo.setChannel(v(row, "channel"));
            Object ct = row.get("createTime");
            vo.setCreateTime(ct != null ? (ct instanceof java.time.LocalDateTime
                    ? ((java.time.LocalDateTime) ct).format(DTF) : ct.toString()) : "");
            return vo;
        }).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", messages);
        data.put("total", total);
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("total_pages", (int) Math.ceil((double) total / pageSize));
        data.put("unread_count", unread);
        return data;
    }

    /** 未读数（API P-N-01） */
    public Map<String, Object> unreadCount(LoginUser user) {
        // 获取未读数量
        return Map.of("unread_count", messageMapper.countUnread(phoneOf(user)));
    }

    /** 标记单条已读（API P-N-02） */
    @Transactional
    public void markRead(LoginUser user, Long id) {
        // 标记为已读
        String phone = phoneOf(user);
        int affected = messageMapper.markRead(phone, id);
        if (affected == 0) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "消息不存在");
        }
    }

    /** 全部已读（API P-N-02） */
    @Transactional
    public void markAllRead(LoginUser user) {
        // 全部标记已读
        messageMapper.markAllRead(phoneOf(user));
    }

    /** 查询通知设置（API P-N-03） */
    public Map<String, Object> getSettings(LoginUser user) {
        NotificationSettings s = settingsMapper.selectByPhone(phoneOf(user));
        return Map.of("sms_enabled", s != null ? s.getSmsEnabled() : 1);
    }

    /** 更新通知设置（API P-N-03） */
    @Transactional
    public void updateSettings(LoginUser user, NotificationSettingsRequest req) {
        // 更新配置信息
        String phone = phoneOf(user);
        NotificationSettings existing = settingsMapper.selectByPhone(phone);
        NotificationSettings s = new NotificationSettings();
        s.setPhone(phone);
        s.setSmsEnabled(req.getSmsEnabled() != null ? req.getSmsEnabled() : 1);
        if (existing == null) {
            settingsMapper.insert(s);
        } else {
            settingsMapper.update(s);
        }
    }

    private static String v(Map<String, Object> m, String k) {
        Object val = m.get(k);
        return val != null ? val.toString() : null;
    }
}
