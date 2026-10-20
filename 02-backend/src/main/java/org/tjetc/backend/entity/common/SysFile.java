package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通用文件表 sys_file（API 12.1 通用文件上传）
 * 一次上传、多处引用：业务接口仅保存 file_url 引用
 */
@Data
@NoArgsConstructor
public class SysFile {

    private Long id;
    /** 业务类型：appeal_attachment/dispose_proof/dispose_photo/avatar/archive_export */
    private String bizType;
    /** 关联工单ID */
    private Long orderId;
    /** 关联父诉求ID（市民提交附件） */
    private Long appealId;
    /** 原始文件名 */
    private String fileName;
    /** 文件访问地址（相对路径） */
    private String fileUrl;
    private Long fileSize;
    /** MIME类型 */
    private String fileType;
    private Long uploadUserId;
    private LocalDateTime uploadTime;
}
