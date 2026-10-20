package org.tjetc.backend.entity.staff;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 处置凭证附件表 order_dispose_attachment（需求 表20 / API 11.1.2）
 * 凭证/现场照片，最多5个，单个≤10MB
 */
@Data
@NoArgsConstructor
public class OrderDisposeAttachment {

    private Long id;
    private Long orderId;
    /** 文件原始名称 */
    private String fileName;
    /** 文件存储路径 */
    private String fileUrl;
    private Long fileSize;
    private String fileType;
    /** 附件类型：CREDENTIAL（凭证）/SCENE_PHOTO（现场照片） */
    private String attachmentType;
    private Long uploadUserId;
    private LocalDateTime uploadTime;
}
