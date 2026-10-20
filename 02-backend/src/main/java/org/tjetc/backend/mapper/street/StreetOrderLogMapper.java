package org.tjetc.backend.mapper.street;

import org.apache.ibatis.annotations.Mapper;
import org.tjetc.backend.entity.common.OrderLog;

import java.util.List;

/**
 * 街道办端工单流转日志 Mapper（street_order_log，全量副本）——街道办端时间线数据源
 */
@Mapper
public interface StreetOrderLogMapper {

    int insert(OrderLog log);

    List<OrderLog> selectByOrderId(Long orderId);
}
