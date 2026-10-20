package org.tjetc.backend.mapper.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.common.SysMessage;

/**
 * 站内消息 Mapper（需求 表27 / API 4.6-4.8）
 */
@Mapper
public interface SysMessageMapper {

    int insert(SysMessage message);

    /** 消息分页查询（API 4.6） */
    java.util.List<java.util.Map<String, Object>> selectByPhonePage(
            @Param("phone") String phone,
            @Param("offset") int offset,
            @Param("size") int size);

    long countByPhone(@Param("phone") String phone);

    /** 标记单条已读（API 4.7） */
    int markRead(@Param("phone") String phone, @Param("id") Long id);

    /** 全部已读（API 4.7） */
    int markAllRead(@Param("phone") String phone);

    /** 未读数（API 4.6） */
    long countUnread(@Param("phone") String phone);

    /** 工作人员消息分页（部门端/街道办端，按 user_id 维度） */
    java.util.List<java.util.Map<String, Object>> selectByUserIdPage(
            @Param("userId") Long userId,
            @Param("offset") int offset,
            @Param("size") int size);

    long countByUserId(@Param("userId") Long userId);

    /** 标记单条已读（带 user_id 校验防越权） */
    int markReadByUserId(@Param("userId") Long userId, @Param("id") Long id);

    /** 全部已读 */
    int markAllReadByUserId(@Param("userId") Long userId);

    /** 未读数（按 user_id） */
    long countUnreadByUserId(@Param("userId") Long userId);
}
