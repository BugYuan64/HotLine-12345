package org.tjetc.backend.mapper.citizen;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.citizen.AppealChild;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 子诉求 Mapper（appeal_child）：AI 拆分出的单个事件，用于生成/归并工单的最小单位
 */
@Mapper
public interface AppealChildMapper {

    AppealChild selectById(@Param("id") Long id);

    /** 按子诉求编号精确查询（市民按子诉求定位评价/重开用） */
    AppealChild selectByNo(@Param("appealNo") String appealNo);

    /** 批量查子诉求（工单详情展示合并子诉求用） */
    List<AppealChild> selectByIds(@Param("ids") List<Long> ids);

    /** 子诉求总量统计（全局数据统计 API 8.4 诉求总数口径） */
    long countAll(@Param("start") LocalDateTime start);

    /** 诉求编号当日序号（AS+时间戳+序号，子表内唯一），用于生成诉求编号 */
    long countByPrefix(@Param("prefix") String prefix);

    /** 管理端子诉求多条件分页查询（API 6.1）：appealNo/status/keyword */
    List<AppealChild> selectPage(@Param("appealNo") String appealNo,
                                 @Param("status") String status,
                                 @Param("keyword") String keyword,
                                 @Param("offset") int offset,
                                 @Param("size") int size);

    long countPage(@Param("appealNo") String appealNo,
                   @Param("status") String status,
                   @Param("keyword") String keyword);

    /** 待生成工单的子诉求（status=PROCESSING），供定时任务扫描 */
    List<AppealChild> selectUnorderedChildren(@Param("limit") int limit);

    /** AI 流水线扫描：待AI审核（AUDIT_PENDING）的子诉求 */
    List<AppealChild> selectAuditPending(@Param("limit") int limit);

    /** 标题阶段扫描：待AI生成标题（TITLE_PENDING）的子诉求 */
    List<AppealChild> selectTitlePendingChildren(@Param("limit") int limit);

    /** OTHER 一级分类队列：AI一级分类中（CLASSIFYING）的子诉求 */
    List<AppealChild> selectClassifyingChildren(@Param("limit") int limit);

    /** AI二级分类队列：AI二级分类中（CLASSIFYING_SUB，进程中断孤儿恢复）的子诉求 */
    List<AppealChild> selectClassifyingSubChildren(@Param("limit") int limit);

    /** 原子领取子诉求进入 AI 审核：AUDIT_PENDING → AUDITING，返回受影响行数（0 表示已被处理） */
    int claimForAudit(@Param("id") Long id);

    /** 查询某父诉求的全部子诉求 */
    List<AppealChild> selectChildrenByParentId(@Param("parentId") Long parentId);

    /** 市民端列表聚合：按父诉求ID批量查子诉求及其工单当前状态（LEFT JOIN，未建单子诉求工单字段为 NULL） */
    List<Map<String, Object>> selectChildrenWithOrderByParentIds(@Param("parentIds") List<Long> parentIds);

    /** 父诉求的子诉求数量（删除父诉求前校验：有关联子诉求不可删除） */
    long countChildrenByParentId(@Param("parentId") Long parentId);

    // ---- 子诉求事件数口径（预判/预警数据源） ----

    /** 按日聚合子诉求事件数（预判统计桥）：返回 [{d, category, region, cnt}] */
    List<Map<String, Object>> aggregateChildrenDailyByCategoryRegion(@Param("days") int days);

    /** 近期子诉求文本（热点主题聚类用）：返回 [{category, region, title, content}] */
    List<Map<String, Object>> selectRecentChildrenTexts(@Param("days") int days);

    int insert(AppealChild appeal);

    /** 管理端编辑诉求：更新诉求可编辑字段 */
    int update(AppealChild appeal);

    /** 原子认领子诉求生成工单：仅 PROCESSING → ORDERED，返回受影响行数（0 表示已被处理） */
    int claimForOrder(@Param("id") Long id);

    /** 管理端删除诉求（物理删除） */
    int deleteById(@Param("id") Long id);

    /** 按父诉求软删除其全部子诉求（市民端删除诉求：父+子一并置 is_deleted=1，保留历史） */
    int deleteByParentId(@Param("parentId") Long parentId);
}