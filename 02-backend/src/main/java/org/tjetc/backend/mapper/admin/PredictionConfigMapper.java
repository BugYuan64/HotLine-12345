package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.tjetc.backend.entity.admin.PredictionConfig;

@Mapper
public interface PredictionConfigMapper {

    PredictionConfig selectFirst();

    int update(PredictionConfig config);

    int insert(PredictionConfig config);
}