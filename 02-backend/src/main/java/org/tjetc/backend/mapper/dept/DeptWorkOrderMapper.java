package org.tjetc.backend.mapper.dept;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.common.WorkOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 部门端工单 Mapper（dept_work_order 副本表）——三端数据解耦
 * 【副本不变式】本表有行 ⟺ 管理端工单 department 非空且状态≠PENDING_DISPATCH；
 * 副本由 OrderCopySyncService 在每次流转事务内从 admin_work_order 刷新（先删后拷，保证逐字段镜像）。
 * 部门端列表/详情/工作台/审核/汇总只读本表，不触碰管理端与街道办端数据。
 */
@Mapper
public interface DeptWorkOrderMapper {

    /** 按主键查副本（is_deleted=0），不存在表示工单未分派至部门或已回退管理端 */
    WorkOrder selectById(Long id);

    /** 从管理端权威表整行拷贝生成/刷新副本（SQL 级镜像，含实体未映射列） */
    int copyFromAdmin(@Param("id") Long id);

    /** 物理删除副本（工单回退管理端池/被删除时调用） */
    int deleteById(@Param("id") Long id);

    // ---- 部门端权限内工单查询（API 9.5）：本分类（category=user_type）+ 本区县（county_code=region_code）工单 ----

    List<Map<String, Object>> selectPageDept(@Param("orderNo") String orderNo,
                                             @Param("keyword") String keyword,
                                             @Param("status") String status,
                                             @Param("statuses") List<String> statuses,
                                             @Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime,
                                             @Param("category") String category,
                                             @Param("streetOffice") String streetOffice,
                                             @Param("countyCode") String countyCode,
                                             @Param("offset") int offset,
                                             @Param("size") int size);

    long countPageDept(@Param("orderNo") String orderNo,
                       @Param("keyword") String keyword,
                       @Param("status") String status,
                       @Param("statuses") List<String> statuses,
                       @Param("startTime") LocalDateTime startTime,
                       @Param("endTime") LocalDateTime endTime,
                       @Param("category") String category,
                       @Param("streetOffice") String streetOffice,
                       @Param("countyCode") String countyCode);

    // ---- 待部门审核列表（API 11.2.1）：STREET_DONE 状态，本分类+本区县过滤 ----

    List<Map<String, Object>> selectAuditingList(@Param("streetOffice") String streetOffice,
                                                 @Param("disposeTag") String disposeTag,
                                                 @Param("isOverdue") Boolean isOverdue,
                                                 @Param("startTime") LocalDateTime startTime,
                                                 @Param("endTime") LocalDateTime endTime,
                                                 @Param("department") String department,
                                                 @Param("countyCode") String countyCode,
                                                 @Param("offset") int offset,
                                                 @Param("size") int size);

    long countAuditingList(@Param("streetOffice") String streetOffice,
                           @Param("disposeTag") String disposeTag,
                           @Param("isOverdue") Boolean isOverdue,
                           @Param("startTime") LocalDateTime startTime,
                           @Param("endTime") LocalDateTime endTime,
                           @Param("department") String department,
                           @Param("countyCode") String countyCode);

    // ---- 部门工作台统计：本分类+本区县工单概况 ----

    Map<String, Object> workbenchSummary(@Param("category") String category,
                                         @Param("countyCode") String countyCode,
                                         @Param("year") int year,
                                         @Param("month") int month);

    // ---- 部门汇总上报（API 11.2.7）：按街道办聚合处置统计 ----

    List<Map<String, Object>> selectDeptReport(@Param("department") String department,
                                               @Param("countyCode") String countyCode,
                                               @Param("start") LocalDateTime start);

    // ---- 部门端诉求趋势折线图（工作台）：按分类统计每日诉求量 ----

    /**
     * 按分类统计近 N 天每日诉求量（部门工作台「本部门诉求趋势」折线图数据源）
     *
     * @param days        统计天数（近 N 天，含今天）
     * @param category    一级分类 code（部门端固定为登录部门 user_type，前端不可传）
     * @param subCategory 二级分类名（null = 该一级整体总括）
     * @param region      区县名（null = 不过滤；工单 region 为「区县+街道」拼接，按前缀匹配只筛到区级）
     * @param countyCode  本账号区县编码（56 部门口径：部门端数据按分类+区县隔离）
     * @return [{ stat_date: yyyy-MM-dd, stat_count: 数量 }]
     */
    List<Map<String, Object>> selectDailyCounts(@Param("days") Integer days,
                                                @Param("category") String category,
                                                @Param("subCategory") String subCategory,
                                                @Param("region") String region,
                                                @Param("countyCode") String countyCode);
}
