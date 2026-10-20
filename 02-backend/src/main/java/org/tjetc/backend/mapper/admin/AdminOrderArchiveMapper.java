package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 管理端终审归档 Mapper（admin_order_archive 独立归档表）——归档数据独立存储
 *
 * 快照语义：归档动作（终审确认归档 FINAL / 手动归档 MANUAL / 办结满7天自动归档 AUTO）
 * 触发时整行快照自 admin_work_order（SQL 级镜像，含实体未映射列）；
 * 重开审批通过时删除快照（工单回到分派流程，归档页不再展示）；
 * 同工单重复归档（重开后再归档）覆盖更新（一单一快照，最新为准）。
 * 主表 admin_work_order 保留原行仅置 is_archived=1（部门/街道副本镜像与未读统计不受影响）。
 * 终审归档页（archived=1 路由）/ 归档数据管理 / 归档导出 只读本表。
 */
@Mapper
public interface AdminOrderArchiveMapper {

    /** 整行拷贝（SQL 级镜像），幂等：调用方先删除同 id 旧快照 */
    int copyFromAdmin(@Param("id") Long id);

    /** 删除归档快照（重开审批通过时调用） */
    int deleteById(@Param("id") Long id);

    // ---- 终审归档列表（终审归档页 archived=1 路由，API 6.2 同构列 + 时间段筛选）----

    List<Map<String, Object>> selectPage(@Param("orderNo") String orderNo,
                                         @Param("category") String category,
                                         @Param("level") String level,
                                         @Param("region") String region,
                                         @Param("status") String status,
                                         @Param("keyword") String keyword,
                                         @Param("startDate") String startDate,
                                         @Param("timeStart") String timeStart,
                                         @Param("timeEnd") String timeEnd,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    long countPage(@Param("orderNo") String orderNo,
                   @Param("category") String category,
                   @Param("level") String level,
                   @Param("region") String region,
                   @Param("status") String status,
                   @Param("keyword") String keyword,
                   @Param("startDate") String startDate,
                   @Param("timeStart") String timeStart,
                   @Param("timeEnd") String timeEnd);

    // ---- 待终审工单列表（API 11.3.1 兼容路径）----

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

    // ---- 归档数据列表（API 11.3.7）----

    List<Map<String, Object>> selectArchives(@Param("year") String year,
                                             @Param("department") String department,
                                             @Param("category") String category,
                                             @Param("offset") int offset,
                                             @Param("size") int size);

    long countArchives(@Param("year") String year,
                       @Param("department") String department,
                       @Param("category") String category);
}
