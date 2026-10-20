package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.PredictionResult;

import java.util.List;

@Mapper
public interface PredictionResultMapper {

    int insertBatch(@Param("list") List<PredictionResult> list);

    List<PredictionResult> selectPage(@Param("category") String category,
                                      @Param("region") String region,
                                      @Param("period") String period,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    long countPage(@Param("category") String category,
                   @Param("region") String region,
                   @Param("period") String period);

    /** 动态预判下钻：最近一批预判结果按区县/一级/二级/周期筛选 */
    List<PredictionResult> selectLatest(@Param("category") String category,
                                        @Param("subCategory") String subCategory,
                                        @Param("region") String region,
                                        @Param("period") String period);

    /** 已回填实际工单数的记录（预判效果评估用） */
    List<PredictionResult> selectFilled();

    /** 未回填实际工单数的记录（P0-2 周期结束后回填） */
    List<PredictionResult> selectUnfilled();

    /** 回填实际工单数与命中状态（P0-2） */
    int updateActual(@Param("id") Long id,
                     @Param("actualCount") Integer actualCount,
                     @Param("hitStatus") String hitStatus);
}