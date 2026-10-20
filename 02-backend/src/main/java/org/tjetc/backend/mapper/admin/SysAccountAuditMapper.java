package org.tjetc.backend.mapper.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.admin.SysAccountAudit;

import java.util.List;
import java.util.Map;

@Mapper
public interface SysAccountAuditMapper {

    int insert(SysAccountAudit audit);

    /**
     * 最近一次驳回原因（登录时展示，需求 5(4)）
     */
    String selectLatestRejectReason(Long userId);

    /** 审核列表分页查询（API 5.3，关联 sys_user 带出申请人信息） */
    List<Map<String, Object>> selectPage(@Param("status") String status,
                                         @Param("name") String name,
                                         @Param("jobNo") String jobNo,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    /** 审核列表总数（API 5.3） */
    long countPage(@Param("status") String status,
                   @Param("name") String name,
                   @Param("jobNo") String jobNo);

    /** 按主键查询审核记录 */
    SysAccountAudit selectById(Long id);

    /** 更新审核结果（状态/审核人/时间/驳回原因） */
    int updateAudit(SysAccountAudit audit);
}