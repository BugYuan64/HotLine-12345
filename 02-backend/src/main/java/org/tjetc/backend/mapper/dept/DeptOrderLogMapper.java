package org.tjetc.backend.mapper.dept;

import org.apache.ibatis.annotations.Mapper;
import org.tjetc.backend.entity.common.OrderLog;

import java.util.List;

/**
 * 部门端工单流转日志 Mapper（dept_order_log，全量副本）——部门端时间线数据源
 */
@Mapper
public interface DeptOrderLogMapper {

    int insert(OrderLog log);

    List<OrderLog> selectByOrderId(Long orderId);
}
