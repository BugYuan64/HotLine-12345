package org.tjetc.backend.mapper.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.common.WorkOrderAppeal;

import java.util.List;

/**
 * 工单-子诉求归并关联表 Mapper（第二阶段去重归并）
 */
@Mapper
public interface WorkOrderAppealMapper {

    int insert(WorkOrderAppeal record);

    /** 子诉求是否已关联工单（唯一约束 uk_appeal 保障一致性） */
    int countByAppealId(@Param("appealId") Long appealId);

    /** 反查子诉求关联的工单ID（市民反馈闭环多子诉求→单工单兼容） */
    Long selectOrderIdByAppealId(@Param("appealId") Long appealId);

    /** 删除某子诉求的关联（子诉求解绑工单时调用） */
    int deleteByAppealId(@Param("appealId") Long appealId);

    /** 重选主诉求：同工单下除指定子诉求外的一条关联子诉求ID（优先 is_primary=1） */
    Long selectAnotherAppealId(@Param("orderId") Long orderId,
                               @Param("excludeAppealId") Long excludeAppealId);

    /** 查工单关联的所有子诉求ID（工单详情展示合并子诉求用） */
    List<Long> selectAppealIdsByOrderId(@Param("orderId") Long orderId);
}