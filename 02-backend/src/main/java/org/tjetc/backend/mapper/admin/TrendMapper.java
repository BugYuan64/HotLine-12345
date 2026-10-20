package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 诉求趋势对比 Mapper（预警与预判页折线图）：
 * 按一级/二级分类统计每日诉求量（数据源 admin_work_order，分类已确定的工单；三端解耦后管理端统计读权威表）
 */
@Mapper
public interface TrendMapper {

    /**
     * 按分类统计近 N 天每日诉求量
     *
     * @param days        统计天数（近 N 天，含今天）
     * @param category    一级分类 code（null = 不过滤）
     * @param subCategory 二级分类名（null = 该一级整体）
     * @param region      区县名（null = 不过滤；工单 region 为「区县+街道」拼接，按前缀匹配只筛到区级）
     * @return [{ stat_date: yyyy-MM-dd, stat_count: 数量 }]
     */
    List<Map<String, Object>> selectDailyCounts(@Param("days") Integer days,
                                                @Param("category") String category,
                                                @Param("subCategory") String subCategory,
                                                @Param("region") String region);
}
