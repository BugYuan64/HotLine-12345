package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.PredictionTrend;

import java.util.List;

/**
 * 预判趋势 Mapper（需求 9(6) 趋势折线图）
 */
@Mapper
public interface PredictionTrendMapper {

    int insertBatch(@Param("list") List<PredictionTrend> list);

    /** 查询指定周期最新一批趋势点（按生成时间最大的一批） */
    List<PredictionTrend> selectLatest(@Param("period") String period);
}