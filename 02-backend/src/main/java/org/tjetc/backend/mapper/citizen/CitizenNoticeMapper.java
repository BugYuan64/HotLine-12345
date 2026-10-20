package org.tjetc.backend.mapper.citizen;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.common.CitizenAppeal;
import org.tjetc.backend.entity.common.CitizenNotice;
import org.tjetc.backend.entity.common.Notice;

import java.util.List;

/**
 * 市民端 Mapper（citizen_notice 公开通知 + citizen_appeal 诉求提交记录）——四端数据解耦
 * 市民端只接收：公开通知只读（发布方联动状态）；诉求提交记录在提交时写入。
 */
@Mapper
public interface CitizenNoticeMapper {

    /** 公开通知分页（市民端首页"通知公告"，status=1，置顶优先；keyword 标题/正文/摘要模糊匹配） */
    List<Notice> selectPublicPage(@Param("keyword") String keyword,
                                  @Param("offset") int offset, @Param("size") int size);

    long countPublic(@Param("keyword") String keyword);

    /** 首页顶部最多 N 条 */
    List<Notice> selectPublicTop(@Param("limit") int limit);

    /** 详情（status=1） */
    CitizenNotice selectByNoticeId(@Param("noticeId") Long noticeId);

    /** 发布方联动：删除公开副本 */
    int deleteByNoticeId(@Param("noticeId") Long noticeId);

    /** 发布方联动：下架/重新展示 */
    int updateStatusByNoticeId(@Param("noticeId") Long noticeId, @Param("status") Integer status);

    /** 发布方编辑联动：内容传播 */
    int propagateEdit(@Param("noticeId") Long noticeId,
                      @Param("title") String title,
                      @Param("content") String content,
                      @Param("summary") String summary);

    /** 扇出新副本（发布） */
    int insert(CitizenNotice notice);

    // ---- 市民诉求提交记录（citizen_appeal）----

    /** 市民提交诉求时独立落库（与 appeal_parent 同事务） */
    int insertAppeal(CitizenAppeal appeal);

    /** 按手机号查本市民的提交记录（"我的诉求"独立数据源） */
    List<CitizenAppeal> selectAppealsByPhone(@Param("citizenPhone") String citizenPhone);
}
