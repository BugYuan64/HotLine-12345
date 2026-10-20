package org.tjetc.backend.mapper.citizen;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.citizen.OrderEvaluate;

import java.util.List;
import java.util.Map;

/**
 * 满意度评价 Mapper（API 11.4：办结后7天内评价，提交后不可修改）
 */
@Mapper
public interface OrderEvaluateMapper {

    int insert(OrderEvaluate evaluate);

    OrderEvaluate selectByOrderId(Long orderId);

    /** 更新重开申请（市民端 API 11.4.3） */
    int updateReopenApply(OrderEvaluate evaluate);

    /** 更新重开审批结果（管理端 API 11.3.8） */
    int updateReopenApprove(OrderEvaluate evaluate);
}