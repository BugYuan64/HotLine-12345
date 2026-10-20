package org.tjetc.backend.service.common;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.citizen.MessageVO;
import org.tjetc.backend.mapper.common.SysMessageMapper;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作人员消息站服务（部门端/街道办端共用，角色中立）：
 * 按 user_id 维度读取站内消息（预警推送/分发等写入的工作人员消息），
 * 提供消息列表 / 未读数 / 单条已读 / 全部已读。
 * 不包含任何 user_type 分支，部门端与街道办端通过各自 Controller 复用本服务。
 */
@Service
@RequiredArgsConstructor
public class InboxMessageService {

    private final SysMessageMapper messageMapper;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Long userIdOf(Long userId) {
        // 校验工作人员身份（登录态缺失时抛未登录）
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        return userId;
    }

    /** 消息列表：分页 + 未读数，返回结构与公众端一致（list 元素字段为驼峰） */
    public Map<String, Object> list(Long userId, int page, int pageSize) {
        // 查询消息列表
        Long uid = userIdOf(userId);
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> rows = messageMapper.selectByUserIdPage(uid, offset, pageSize);
        long total = messageMapper.countByUserId(uid);
        long unread = messageMapper.countUnreadByUserId(uid);

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

    /** 未读数 */
    public Map<String, Object> unreadCount(Long userId) {
        // 查询未读数
        return Map.of("unread_count", messageMapper.countUnreadByUserId(userIdOf(userId)));
    }

    /** 标记单条已读 */
    @Transactional
    public void markRead(Long userId, Long id) {
        // 标记单条已读
        int affected = messageMapper.markReadByUserId(userIdOf(userId), id);
        if (affected == 0) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "消息不存在");
        }
    }

    /** 全部已读 */
    @Transactional
    public void markAllRead(Long userId) {
        // 全部标记已读
        messageMapper.markAllReadByUserId(userIdOf(userId));
    }

    private static String v(Map<String, Object> m, String k) {
        Object val = m.get(k);
        return val != null ? val.toString() : null;
    }
}