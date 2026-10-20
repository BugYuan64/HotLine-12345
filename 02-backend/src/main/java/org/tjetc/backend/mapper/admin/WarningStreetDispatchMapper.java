package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.WarningStreetDispatch;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WarningStreetDispatchMapper {

    /** 批量插入分发关系（部门端分发预警到多个街道办） */
    int insertBatch(@Param("list") List<WarningStreetDispatch> list);

    /** 某预警的全部分发记录（分发进度查询） */
    List<WarningStreetDispatch> selectByWarningId(@Param("warningId") Long warningId);

    /** 按预警 + 街道名查单条（街道办响应前的归属校验） */
    WarningStreetDispatch selectByWarningAndStreet(@Param("warningId") Long warningId,
                                                   @Param("streetOffice") String streetOffice);

    /** 街道办标记本街道响应 */
    int updateRespond(@Param("id") Long id,
                      @Param("respondTime") LocalDateTime respondTime,
                      @Param("respondUserId") Long respondUserId,
                      @Param("respondUserName") String respondUserName);

    /** 部门/管理端标记响应后，将该预警全部分发记录置为已响应（与主表同步，街道办端才可见） */
    int updateAllRespond(@Param("warningId") Long warningId,
                         @Param("respondTime") LocalDateTime respondTime);

    /** 街道办工作台待响应预警数：分发给本街道且已发送、仍未响应的预警计数 */
    int countUnrespondedByStreet(@Param("streetOffice") String streetOffice);
}