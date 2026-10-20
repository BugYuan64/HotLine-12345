package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.WarningRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface WarningRecordMapper {

    int insert(WarningRecord record);

    /** 回填 AI 总结与应对措施（触发流程：先入库拿 ID，再调 AI 后回填） */
    int updateAiContent(WarningRecord record);

    /** 按 ID 查询单条预警记录（供审核/编辑/发送/部门响应前归属校验） */
    WarningRecord selectById(@Param("id") Long id);

    /** 更新审核状态与审核时间（发送 SENT / 驳回 REJECTED） */
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("reviewTime") LocalDateTime reviewTime);

    /** 审核中编辑 AI 总结与应对措施 */
    int updateContent(@Param("id") Long id, @Param("aiSummary") String aiSummary, @Param("measure") String measure);

    /** 标记预警已响应（需求 11(4)：响应闭环，置为 RESPONDED 并记录响应时间） */
    int updateRespond(@Param("id") Long id);

    /** 街道办响应后同步主表：仅当该预警全部分发街道办均已响应时才置主表已响应（避免单街道响应掩盖其他未响应） */
    int updateRespondIfAllDispatched(@Param("warningId") Long warningId);

    /** 查询超时未响应的紧急预警（升级提醒扫描 B3：URGENT 且超过时间仍未响应，仅限已发送的记录） */
    List<Map<String, Object>> selectUnhandledUrgentOlderThan(@Param("threshold") LocalDateTime threshold);

    /** 管理端待办聚合（todo/summary）：超时未响应紧急预警数（口径与升级扫描一致） */
    long countUnhandledUrgentOlderThan(@Param("threshold") LocalDateTime threshold);

    /** 部门工作台待跟进预警数：已发送给本部门（department 含本分类code或「区县:分类」复合键）且响应闭环未完成的预警计数 */
    long countUnrespondedByDept(@Param("dept") String dept, @Param("countyCode") String countyCode);

    List<Map<String, Object>> selectPage(@Param("warningLevel") String warningLevel,
                                         @Param("category") String category,
                                         @Param("region") String region,
                                         @Param("status") String status,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    long countPage(@Param("warningLevel") String warningLevel,
                   @Param("category") String category,
                   @Param("region") String region,
                   @Param("status") String status,
                   @Param("startTime") LocalDateTime startTime,
                   @Param("endTime") LocalDateTime endTime);

    /** 部门端：归属本部门（department 含本分类code=全市可见，或「区县:分类」复合键=仅该区县可见）的已发送预警记录分页 */
    List<Map<String, Object>> selectStaffPage(@Param("dept") String dept,
                                              @Param("countyCode") String countyCode,
                                              @Param("subCategory") String subCategory,
                                              @Param("warningLevel") String warningLevel,
                                              @Param("region") String region,
                                              @Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime,
                                              @Param("offset") int offset,
                                              @Param("size") int size);

    long countStaffPage(@Param("dept") String dept,
                        @Param("countyCode") String countyCode,
                        @Param("subCategory") String subCategory,
                        @Param("warningLevel") String warningLevel,
                        @Param("region") String region,
                        @Param("startTime") LocalDateTime startTime,
                        @Param("endTime") LocalDateTime endTime);

    /** 街道办端：仅分发给本街道（warning_street_dispatch.street_office = 账号辖区）的已发送预警分页（本辖区数据隔离） */
    List<Map<String, Object>> selectStreetPage(@Param("subCategory") String subCategory,
                                               @Param("warningLevel") String warningLevel,
                                               @Param("region") String region,
                                               @Param("startTime") LocalDateTime startTime,
                                               @Param("endTime") LocalDateTime endTime,
                                               @Param("offset") int offset,
                                               @Param("size") int size,
                                               @Param("streetOffice") String streetOffice);

    long countStreetPage(@Param("subCategory") String subCategory,
                         @Param("warningLevel") String warningLevel,
                         @Param("region") String region,
                         @Param("startTime") LocalDateTime startTime,
                         @Param("endTime") LocalDateTime endTime,
                         @Param("streetOffice") String streetOffice);

    /** 当日已触发次数（同规则当日去重，避免每日重复预警） */
    int countByRuleToday(@Param("ruleId") Long ruleId, @Param("dayStart") LocalDateTime dayStart);

    /** 编号生成：统计当日预警数 */
    int countByPrefix(@Param("prefix") String prefix);
}