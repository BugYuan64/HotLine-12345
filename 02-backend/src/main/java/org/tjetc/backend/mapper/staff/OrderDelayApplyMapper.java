package org.tjetc.backend.mapper.staff;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.staff.OrderDelayApply;

import java.util.List;
import java.util.Map;

/**
 * 延期申请 Mapper（API 11.1.4/11.2.6：最多2次，每次≤3个工作日）
 */
@Mapper
public interface OrderDelayApplyMapper {

    int insert(OrderDelayApply apply);

    OrderDelayApply selectById(Long id);

    List<OrderDelayApply> selectByOrderId(Long orderId);

    /** 待审批列表（部门维度，按分类+区县隔离，56=7区县×8分类口径） */
    List<Map<String, Object>> selectPendingByDept(@Param("department") String department,
                                                  @Param("countyCode") String countyCode);

    /** 待审批延期数（工作台待办卡，口径与 selectPendingByDept 一致） */
    long countPendingByDept(@Param("department") String department,
                            @Param("countyCode") String countyCode);

    int updateApprove(OrderDelayApply apply);
}