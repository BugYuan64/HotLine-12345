package org.tjetc.backend.mapper.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.common.NotificationSettings;

/**
 * 通知偏好设置 Mapper（API 4.8）
 */
@Mapper
public interface NotificationSettingsMapper {

    NotificationSettings selectByPhone(@Param("phone") String phone);

    int insert(NotificationSettings settings);

    int update(NotificationSettings settings);

    int deleteByPhone(@Param("phone") String phone);
}
