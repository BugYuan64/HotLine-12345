package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.common.WorkOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工单 Mapper（P0-3 预判聚合 / P0-4 准确率 / P0-5 预警 / P0-6 流转闭环）
 */
@Mapper
public interface AdminWorkOrderMapper {

    // ---- P0-3 诉求预判聚合 ----

    /**
     * 按日聚合工单量：返回 [{d:日期, category:分类, region:辖区, cnt:数量}]
     */
    List<Map<String, Object>> aggregateDailyByCategoryRegion(@Param("days") int days);

    /**
     * 近期工单文本（热点主题聚类用）：返回 [{category, region, title, content}]
     */
    List<Map<String, Object>> selectRecentTexts(@Param("days") int days);

    /** 预判回填（P0-2）：指定分类 + 辖区前缀匹配 + 时间窗口内的实际工单数；subCategory 非空时精确匹配（用于子分类级预测） */
    long countActualForPrediction(@Param("category") String category,
                                  @Param("subCategory") String subCategory,
                                  @Param("region") String region,
                                  @Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end);

    // ---- P0-4 分类准确率统计（API 7.7）：基于已分类工单的平均 AI 置信度 ----

    Double overallAccuracy(@Param("start") LocalDateTime start, @Param("category") String category);

    List<Map<String, Object>> accuracyByCategory(@Param("start") LocalDateTime start, @Param("category") String category);

    long countClassified(@Param("start") LocalDateTime start, @Param("category") String category);

    // ---- P0-5 预警规则匹配（API 8.1）：时间窗口内命中规则的工单数 ----

    /** 预警（P0-5/需求11）：窗口内匹配规则的工单累计关注度（attention_count 求和），新口径数据源 */
    long sumAttentionForWarning(@Param("start") LocalDateTime start,
                                @Param("category") String category,
                                @Param("subCategory") String subCategory,
                                @Param("region") String region);

    /** 预警（P0-5/需求11）：窗口内命中工单的标题+正文（AI 预警总结用），新口径数据源 */
    List<Map<String, Object>> selectWarningTexts(@Param("start") LocalDateTime start,
                                                 @Param("category") String category,
                                                 @Param("subCategory") String subCategory,
                                                 @Param("region") String region);

    /** 预警（需求11）：窗口内命中工单明细（应对措施富文本上下文用，含编号/关注度/分类/辖区/状态） */
    List<Map<String, Object>> selectWarningContext(@Param("start") LocalDateTime start,
                                                   @Param("category") String category,
                                                   @Param("subCategory") String subCategory,
                                                   @Param("region") String region);

    /** 预警规则二级分类建议（API 8.1）：已存在工单的二级分类去重，供前端可选用 */
    List<String> selectDistinctSubCategory(@Param("category") String category);

    // ---- P0-6 工单流转闭环 ----

    WorkOrder selectById(Long id);

    /** 自动派单按需取一张：待分派（PENDING_DISPATCH）且已确定分派部门、未逻辑删除的工单 */
    WorkOrder selectOnePendingDispatch();

    /** 原子领取自动派单：仅当工单仍处于待分派时推进至部门处理中（防建单事务与 worker 竞态重复分派） */
    int claimAutoDispatch(@org.apache.ibatis.annotations.Param("id") Long id,
                          @org.apache.ibatis.annotations.Param("receiveTime") java.time.LocalDateTime receiveTime);

    /** 待分派工单数（自动派单状态展示用，含未确定部门的降级工单） */
    long countPendingDispatch();

    /** 未删除工单总数（工单页分派完成率分母） */
    long countAllNotDeleted();

    /** 新增工单（P1 工单管理使用） */
    int insert(WorkOrder order);

    /** 全字段更新（服务层先查后改，状态变更必须配合 order_log 写入） */
    int update(WorkOrder order);

    WorkOrder selectBySourceAppealId(Long appealId);

    // ---- 第二阶段去重归并 ----

    /** 归并候选（AI 相似度兜底）：同分类 + 同辖区的近期未归档工单 */
List<Map<String, Object>> selectMergeCandidates(@Param("category") String category,
            @Param("subCategory") String subCategory,
                                                    @Param("region") String region,
                                                    @Param("start") LocalDateTime start,
                                                    @Param("limit") int limit);

    // ---- P1 管理端工单管理（API 6.2/6.5/6.7） ----

    /** 管理端工单多条件分页查询（API 6.2）：orderNo/category/level/region/status/keyword/archived */
    List<Map<String, Object>> selectPage(@Param("orderNo") String orderNo,
                                         @Param("category") String category,
                                         @Param("level") String level,
                                         @Param("region") String region,
                                         @Param("status") String status,
                                         @Param("keyword") String keyword,
                                         @Param("startDate") String startDate,
                                         @Param("archived") Integer archived,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    /** 批量导出工单（按 id 列表查询标题/内容/辖区/等级/状态，供 Excel 导出复用导入模板格式） */
    List<Map<String, Object>> selectExportByIds(@Param("ids") List<Long> ids);

    long countPage(@Param("orderNo") String orderNo,
                   @Param("category") String category,
                   @Param("level") String level,
                   @Param("region") String region,
                   @Param("status") String status,
                   @Param("keyword") String keyword,
                   @Param("startDate") String startDate,
                   @Param("archived") Integer archived);

    /** 当前列表筛选条件下（与 countPage 同 where）的待分派工单数：
     *  工单页分派进度分子 = countPendingPage，分母 = countPage（列表总数），
     *  保证仪表盘反映「当前列表集合」而非全表，列表为空时前端显示 100% */
    long countPendingPage(@Param("orderNo") String orderNo,
                          @Param("category") String category,
                          @Param("level") String level,
                          @Param("region") String region,
                          @Param("status") String status,
                          @Param("keyword") String keyword,
                          @Param("startDate") String startDate,
                          @Param("archived") Integer archived);

    /** 工单编号当日序号（WO+日期+序号，全局唯一），用于录入/导入时生成工单编号 */
    long countByPrefix(@Param("prefix") String prefix);

    /** 逻辑删除工单（API 6.7）：保留历史可追溯 */
    int deleteById(@Param("id") Long id);

    /** 待终审工单列表（API 11.3.1） */
    List<Map<String, Object>> selectFinalAuditing(@Param("department") String department,
                                                  @Param("streetOffice") String streetOffice,
                                                  @Param("disposeTag") String disposeTag,
                                                  @Param("isOverdue") Boolean isOverdue,
                                                  @Param("offset") int offset,
                                                  @Param("size") int size);

    long countFinalAuditing(@Param("department") String department,
                            @Param("streetOffice") String streetOffice,
                            @Param("disposeTag") String disposeTag,
                            @Param("isOverdue") Boolean isOverdue);

    /** 归档数据列表（API 11.3.7） */
    List<Map<String, Object>> selectArchives(@Param("year") String year,
                                             @Param("department") String department,
                                             @Param("category") String category,
                                             @Param("offset") int offset,
                                             @Param("size") int size);

    long countArchives(@Param("year") String year,
                       @Param("department") String department,
                       @Param("category") String category);

    /** 重开申请列表（API 11.3.8 审批列表，文档未定义查询接口，按需求补充） */
    List<Map<String, Object>> selectReopenApplications(@Param("offset") int offset, @Param("size") int size);

    long countReopenApplications();

    /** 权限内工单查询（API 9.5）：部门/街道办按归属自动过滤 */
    List<Map<String, Object>> selectPageStaff(@Param("orderNo") String orderNo,
                                              @Param("keyword") String keyword,
                                              @Param("status") String status,
                                              @Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime,
                                              @Param("department") String department,
                                              @Param("streetOffice") String streetOffice,
                                              @Param("offset") int offset,
                                              @Param("size") int size);

    long countPageStaff(@Param("orderNo") String orderNo,
                        @Param("keyword") String keyword,
                        @Param("status") String status,
                        @Param("startTime") LocalDateTime startTime,
                        @Param("endTime") LocalDateTime endTime,
                        @Param("department") String department,
                        @Param("streetOffice") String streetOffice);

    /** 待部门审核列表（API 11.2.1）：部门过滤 + 街道办/处置标签/超时筛选 */
    List<Map<String, Object>> selectAuditingList(@Param("streetOffice") String streetOffice,
                                                 @Param("disposeTag") String disposeTag,
                                                 @Param("isOverdue") Boolean isOverdue,
                                                 @Param("startTime") LocalDateTime startTime,
                                                 @Param("endTime") LocalDateTime endTime,
                                                 @Param("department") String department,
                                                 @Param("offset") int offset,
                                                 @Param("size") int size);

    long countAuditingList(@Param("streetOffice") String streetOffice,
                           @Param("disposeTag") String disposeTag,
                           @Param("isOverdue") Boolean isOverdue,
                           @Param("startTime") LocalDateTime startTime,
                           @Param("endTime") LocalDateTime endTime,
                           @Param("department") String department);

    /**
     * 部门工作台统计：按分类(category)/辖区(region)统计本部门工单概况。
     * 返回：order_total 工单总数、pending_handle 待处理、pending_audit 待部门审核、
     * month_done 本月已办、avg_score 平均部门评分
     */
    Map<String, Object> workbenchSummary(@Param("category") String category,
                                         @Param("region") String region,
                                         @Param("year") int year,
                                         @Param("month") int month);

    // ---- 超时/归档定时任务候选 ----

    /** 超期候选：截止时间已过且未标记超期的在办工单（状态不变仅打标记） */
    List<WorkOrder> selectOverdueCandidates();

    /** 自动归档候选：办结超7天未归档（API 11.3.6） */
    List<WorkOrder> selectAutoArchiveCandidates();

    /** 待接收超时候选（24h 提醒 / 48h 预警，API 11.1.1） */
    List<WorkOrder> selectPendingReceiveBefore(LocalDateTime before);

    /** 部门汇总上报（API 11.2.7）：按街道办聚合处置统计 */
    List<Map<String, Object>> selectDeptReport(@Param("department") String department,
                                               @Param("start") LocalDateTime start);

    // ---- P0-7 全局数据统计（API 8.4/8.5） ----

    long countAll(@Param("start") LocalDateTime start,
                  @Param("category") String category,
                  @Param("region") String region);

    long countByStatus(@Param("status") String status,
                       @Param("start") LocalDateTime start,
                       @Param("category") String category,
                       @Param("region") String region);

    /** 工单状态分布（API 8.4 status_distribution）：按状态统计，随周期/分类/辖区筛选 */
    List<Map<String, Object>> statusDistribution(@Param("start") LocalDateTime start,
                                                 @Param("category") String category,
                                                 @Param("region") String region);

    /** 管理端待办聚合（todo/summary）：按状态统计未删除工单数，不含时间窗口 */
    long countTodoByStatus(@Param("status") String status);

    /** 已分配数量：排除待确认/待更新（API 8.4 assigned） */
    long countAssigned(@Param("start") LocalDateTime start,
                       @Param("category") String category,
                       @Param("region") String region);

    List<Map<String, Object>> categoryDistribution(@Param("start") LocalDateTime start,
                                                   @Param("category") String category,
                                                   @Param("region") String region);

    /** 按分类统计已办结（CLOSED）工单数量：分类柱状图"完成量"数据源 */
    List<Map<String, Object>> categoryDoneDistribution(@Param("start") LocalDateTime start,
                                                       @Param("category") String category,
                                                       @Param("region") String region);

    List<Map<String, Object>> levelDistribution(@Param("start") LocalDateTime start,
                                                @Param("category") String category,
                                                @Param("region") String region);

    /** 趋势数据：按日聚合工单量（API 8.4 trend） */
    List<Map<String, Object>> trendDaily(@Param("start") LocalDateTime start,
                                         @Param("category") String category,
                                         @Param("region") String region);

    /** 趋势数据：按办结时间（close_time）日聚合完成量（趋势图"完成量"数据源） */
    List<Map<String, Object>> trendDoneDaily(@Param("start") LocalDateTime start,
                                             @Param("category") String category,
                                             @Param("region") String region);
}