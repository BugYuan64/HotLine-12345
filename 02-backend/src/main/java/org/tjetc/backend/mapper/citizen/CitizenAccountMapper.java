package org.tjetc.backend.mapper.citizen;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.citizen.CitizenAccount;

/**
 * 市民账号 Mapper（citizen_account）
 */
@Mapper
public interface CitizenAccountMapper {

    /** 按手机号查询 */
    CitizenAccount selectByPhone(@Param("phone") String phone);

    /** 按邮箱查询 */
    CitizenAccount selectByEmail(@Param("email") String email);

    /** 手机号是否已注册 */
    long countByPhone(@Param("phone") String phone);

    /** 邮箱是否已注册 */
    long countByEmail(@Param("email") String email);

    /** 注册（新建市民账号） */
    int insert(CitizenAccount account);
}
