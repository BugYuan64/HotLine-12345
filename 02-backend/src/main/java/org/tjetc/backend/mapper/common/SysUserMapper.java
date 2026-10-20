package org.tjetc.backend.mapper.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.common.SysUser;

import java.util.List;
import java.util.Map;

@Mapper
public interface SysUserMapper {

    SysUser selectById(Long id);

    SysUser selectByJobNo(String jobNo);

    /**
     * 按工号或手机号定位用户（手机号/工号共用登录、忘记密码用）
     */
    SysUser selectByLoginAccount(String account);

    int countByJobNo(String jobNo);

    int countByPhone(String phone);

    int countByEmail(String email);

    int insert(SysUser user);

    int updatePassword(SysUser user);

    /** 改密/重置密码：更新密码并原子递增 pwd_version（DB 为密码版本事实源，旧 Token 立即失效） */
    int updatePasswordWithVersion(@Param("id") Long id, @Param("password") String password);

    /** 读取密码版本（Redis 缓存缺失时回退 DB 用；无记录返回 null） */
    Integer selectPwdVersion(@Param("id") Long id);

    /** 更新审核状态（账号审核通过/驳回后同步用户表，API 5.4/5.5） */
    int updateAuditStatus(@org.apache.ibatis.annotations.Param("userId") Long userId,
                          @org.apache.ibatis.annotations.Param("auditStatus") String auditStatus);

    /**
     * 按归属类型查询启用且已审核通过的用户（预警站内消息推送用）
     */
    java.util.List<SysUser> selectEnabledByUserType(String userType);

    // ---------------------- 用户账号管理（API 5.2/5.6/5.9） ----------------------

    /** 分页查询（API 5.2 GET）：姓名模糊、工号精确、归属类型、账号状态过滤，仅普通工作人员（排除 ADMIN） */
    List<Map<String, Object>> selectPage(@Param("name") String name,
                                         @Param("jobNo") String jobNo,
                                         @Param("userType") String userType,
                                         @Param("status") String status,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    /** 分页总数（API 5.2 GET） */
    long countPage(@Param("name") String name,
                   @Param("jobNo") String jobNo,
                   @Param("userType") String userType,
                   @Param("status") String status);

    /** 更新基础信息（姓名/单位/归属类型/辖区/手机号；密码通过 updatePassword 单独更新） */
    int update(SysUser user);

    /** 账号状态切换（API 5.6） */
    int updateStatus(@Param("userId") Long userId, @Param("status") String status);

    /** 逻辑删除（API 5.2 DELETE）：保留历史操作记录 */
    int logicalDelete(Long id);
}