package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.common.Notice;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端公告主表 Mapper（admin_notice，全量权威）——通知公告四端拆分
 * 所有端（管理端/部门端/街道办）发布的公告均落本表；部门/街道/市民收件箱为其按目标扇出的快照。
 * 另含目标重构查询（读三端收件箱表，管理端编辑回填/接收对象摘要用——管理端监督视角跨端只读）。
 */
@Mapper
public interface AdminNoticeMapper {

    Notice selectById(@Param("id") Long id);

    /** 管理端发布列表（标题/关键词/状态/发布者类型/发布部门/日期区间筛选） */
    List<Notice> selectPage(@Param("title") String title,
                            @Param("keyword") String keyword,
                            @Param("status") Integer status,
                            @Param("publisherType") String publisherType,
                            @Param("publisherDept") String publisherDept,
                            @Param("startTime") LocalDateTime startTime,
                            @Param("endTime") LocalDateTime endTime,
                            @Param("offset") int offset,
                            @Param("size") int size);

    long countPage(@Param("title") String title,
                   @Param("keyword") String keyword,
                   @Param("status") Integer status,
                   @Param("publisherType") String publisherType,
                   @Param("publisherDept") String publisherDept,
                   @Param("startTime") LocalDateTime startTime,
                   @Param("endTime") LocalDateTime endTime);

    int insert(Notice notice);

    /** 动态更新（<set>：仅非空字段），编辑/下架/重发共用 */
    int update(Notice notice);

    int deleteById(@Param("id") Long id);

    /** 按发布者查已发公告：部门端"我发布的"（DEPT+code）/ 街道办端"我发布的"（STREET+null） */
    List<Notice> selectPublishedByPublisher(@Param("publisherType") String publisherType,
                                            @Param("publisherDept") String publisherDept,
                                            @Param("keyword") String keyword,
                                            @Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime,
                                            @Param("offset") int offset,
                                            @Param("size") int size);

    long countPublishedByPublisher(@Param("publisherType") String publisherType,
                                   @Param("publisherDept") String publisherDept,
                                   @Param("keyword") String keyword,
                                   @Param("startTime") LocalDateTime startTime,
                                   @Param("endTime") LocalDateTime endTime);

    // ---- 目标重构（读三端收件箱表：编辑回填/接收对象摘要）----

    /** 某公告扇出到的部门 code 集合 */
    List<String> selectTargetDeptCodes(@Param("noticeId") Long noticeId);

    /** 某公告扇出到的街道办名集合 */
    List<String> selectTargetStreetNames(@Param("noticeId") Long noticeId);

    /** 某公告是否有市民公开副本 */
    long countCitizenCopy(@Param("noticeId") Long noticeId);
}
