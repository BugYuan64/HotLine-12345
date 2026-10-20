package org.tjetc.backend.mapper.street;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.common.WorkOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 街道办端工单 Mapper（street_work_order 副本表）——三端数据解耦
 * 【副本不变式】本表有行 ⟺ 管理端工单 street_office 非空且状态未回退至管理端/部门待分派池；
 * 副本由 OrderCopySyncService 在每次流转事务内从 admin_work_order 刷新（先删后拷，保证逐字段镜像）。
 * 街道办端列表/详情/工作台只读本表（按本辖区过滤），不触碰管理端与部门端数据。
 */
@Mapper
public interface StreetWorkOrderMapper {

    /** 按主键查副本（is_deleted=0），不存在表示工单未分派至本辖区或已回退 */
    WorkOrder selectById(Long id);

    /** 从管理端权威表整行拷贝生成/刷新副本（SQL 级镜像，含实体未映射列） */
    int copyFromAdmin(@Param("id") Long id);

    /** 物理删除副本（工单回退管理端池/被删除时调用） */
    int deleteById(@Param("id") Long id);

    // ---- 街道办端权限内工单查询（API 9.5）：仅本辖区（street_office 前缀匹配）工单 ----

    List<Map<String, Object>> selectPageStreet(@Param("orderNo") String orderNo,
                                               @Param("keyword") String keyword,
                                               @Param("status") String status,
                                               @Param("startTime") LocalDateTime startTime,
                                               @Param("endTime") LocalDateTime endTime,
                                               @Param("streetOffice") String streetOffice,
                                               @Param("offset") int offset,
                                               @Param("size") int size);

    long countPageStreet(@Param("orderNo") String orderNo,
                         @Param("keyword") String keyword,
                         @Param("status") String status,
                         @Param("startTime") LocalDateTime startTime,
                         @Param("endTime") LocalDateTime endTime,
                         @Param("streetOffice") String streetOffice);

    // ---- 街道办工作台统计：本辖区工单概况 ----

    Map<String, Object> workbenchSummaryStreet(@Param("region") String region,
                                               @Param("year") int year,
                                               @Param("month") int month);
}
