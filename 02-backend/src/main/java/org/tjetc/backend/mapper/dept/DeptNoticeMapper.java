package org.tjetc.backend.mapper.dept;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.dto.NoticeVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 部门端通知收件箱 Mapper（dept_notice）——通知公告四端拆分
 * 部门端"收到的通知"列表/未读徽章/已读标记只读本表；发布方编辑/下架/重发/删除时联动本表。
 */
@Mapper
public interface DeptNoticeMapper {

    /** 收到的通知（本部门展示中通知，行内带部门级已读标记；发布者类型/发布部门/关键词/日期区间筛选）
     *  收件匹配：dept_code = 纯分类code（历史「全市该分类」行）或「区县:分类」复合键（56部门口径） */
    List<NoticeVO> selectReceived(@Param("deptCode") String deptCode,
                                  @Param("countyCode") String countyCode,
                                  @Param("publisherType") String publisherType,
                                  @Param("publisherDept") String publisherDept,
                                  @Param("keyword") String keyword,
                                  @Param("startTime") LocalDateTime startTime,
                                  @Param("endTime") LocalDateTime endTime,
                                  @Param("offset") int offset,
                                  @Param("size") int size);

    long countReceived(@Param("deptCode") String deptCode,
                       @Param("countyCode") String countyCode,
                       @Param("publisherType") String publisherType,
                       @Param("publisherDept") String publisherDept,
                       @Param("keyword") String keyword,
                       @Param("startTime") LocalDateTime startTime,
                       @Param("endTime") LocalDateTime endTime);

    /** 详情用：按 notice_id + 部门取收件箱行（不存在 = 未发送给本部门） */
    NoticeVO selectByNoticeAndDept(@Param("noticeId") Long noticeId, @Param("deptCode") String deptCode,
                                   @Param("countyCode") String countyCode);

    /** 点开详情置已读（部门级粒度，幂等） */
    int markRead(@Param("noticeId") Long noticeId, @Param("deptCode") String deptCode,
                 @Param("countyCode") String countyCode);

    /** 未读徽章数（本部门展示中且未读） */
    long countUnread(@Param("deptCode") String deptCode, @Param("countyCode") String countyCode);

    /** 发布方联动：按源公告删除全部部门副本（重发重建/删除公告） */
    int deleteByNoticeId(@Param("noticeId") Long noticeId);

    /** 发布方联动：按源公告下架/重新展示（status 同步） */
    int updateStatusByNoticeId(@Param("noticeId") Long noticeId, @Param("status") Integer status);

    /** 发布方编辑联动：内容/标题/摘要传播（保留已读状态） */
    int propagateEdit(@Param("noticeId") Long noticeId,
                      @Param("title") String title,
                      @Param("content") String content,
                      @Param("summary") String summary);

    /** 目标移除（编辑时接收对象差异同步） */
    int deleteByNoticeAndCode(@Param("noticeId") Long noticeId, @Param("deptCode") String deptCode);

    /** 扇出新副本（发布/目标新增） */
    int insert(org.tjetc.backend.entity.common.DeptNotice notice);
}
