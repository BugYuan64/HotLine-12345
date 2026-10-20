package org.tjetc.backend.mapper.common;

import org.apache.ibatis.annotations.Mapper;
import org.tjetc.backend.entity.common.SysFile;

/**
 * 通用文件 Mapper（API 12.1 通用文件上传：一次上传、多处引用）
 */
@Mapper
public interface SysFileMapper {

    int insert(SysFile file);

    /** 按 ID 查文件元数据（预签名 URL 签发用） */
    SysFile selectById(@org.apache.ibatis.annotations.Param("id") Long id);

    /** 诉求附件：按父诉求ID查附件列表（市民提交时上传） */
    java.util.List<SysFile> selectByAppealId(@org.apache.ibatis.annotations.Param("appealId") Long appealId);

    /** 绑定附件到父诉求（提交时关联，仅未关联的附件可绑定） */
    int updateAppealId(@org.apache.ibatis.annotations.Param("id") Long id,
                       @org.apache.ibatis.annotations.Param("appealId") Long appealId);
}