package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.tjetc.backend.entity.common.OrderLog;

import java.util.List;

/**
 * 管理端工单流转日志 Mapper（admin_order_log，全量）——API 11.1.5 时间线
 * 【状态机闭环】所有状态变更必须写入，禁止绕过；三端数据解耦后本表为管理端时间线数据源
 */
@Mapper
public interface AdminOrderLogMapper {

    int insert(OrderLog log);

    List<OrderLog> selectByOrderId(Long orderId);
}
