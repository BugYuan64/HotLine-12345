package org.tjetc.backend.mapper.street;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.dto.NoticeVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 街道办端通知收件箱 Mapper（street_notice）——通知公告四端拆分
 * 街道办端"收到的通知"列表/未读徽章/已读标记只读本表；发布方编辑/下架/重发/删除时联动本表。
 */
@Mapper
public interface StreetNoticeMapper {

    /** 收到的通知（本街道办展示中通知，行内带已读标记；发布者类型/发布部门/关键词/日期区间筛选） */
    List<NoticeVO> selectReceived(@Param("streetName") String streetName,
                                  @Param("publisherType") String publisherType,
                                  @Param("publisherDept") String publisherDept,
                                  @Param("keyword") String keyword,
                                  @Param("startTime") LocalDateTime startTime,
                                  @Param("endTime") LocalDateTime endTime,
                                  @Param("offset") int offset,
                                  @Param("size") int size);

    long countReceived(@Param("streetName") String streetName,
                       @Param("publisherType") String publisherType,
                       @Param("publisherDept") String publisherDept,
                       @Param("keyword") String keyword,
                       @Param("startTime") LocalDateTime startTime,
                       @Param("endTime") LocalDateTime endTime);

    /** 详情用：按 notice_id + 街道办名取收件箱行（不存在 = 未发送给本街道办） */
    NoticeVO selectByNoticeAndStreet(@Param("noticeId") Long noticeId, @Param("streetName") String streetName);

    /** 点开详情置已读（街道办级粒度，幂等） */
    int markRead(@Param("noticeId") Long noticeId, @Param("streetName") String streetName);

    /** 未读徽章数（本街道办展示中且未读） */
    long countUnread(@Param("streetName") String streetName);

    /** 发布方联动：按源公告删除全部街道办副本 */
    int deleteByNoticeId(@Param("noticeId") Long noticeId);

    /** 发布方联动：按源公告下架/重新展示 */
    int updateStatusByNoticeId(@Param("noticeId") Long noticeId, @Param("status") Integer status);

    /** 发布方编辑联动：内容传播（保留已读状态） */
    int propagateEdit(@Param("noticeId") Long noticeId,
                      @Param("title") String title,
                      @Param("content") String content,
                      @Param("summary") String summary);

    /** 目标移除（编辑时接收对象差异同步） */
    int deleteByNoticeAndName(@Param("noticeId") Long noticeId, @Param("streetName") String streetName);

    /** 扇出新副本（发布/目标新增） */
    int insert(org.tjetc.backend.entity.common.StreetNotice notice);
}
