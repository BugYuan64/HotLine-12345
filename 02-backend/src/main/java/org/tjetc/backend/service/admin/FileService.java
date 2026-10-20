package org.tjetc.backend.service.admin;

import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.entity.staff.OrderDisposeAttachment;
import org.tjetc.backend.entity.common.SysFile;
import org.tjetc.backend.mapper.staff.OrderDisposeAttachmentMapper;
import org.tjetc.backend.mapper.common.SysFileMapper;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 通用文件服务（API 12.1）：MinIO 对象存储 + sys_file 元数据配合（需求：数据库和 MinIO 配合存储）。
 * 上传：文件本体写 MinIO（对象键 {biz_type}/{yyyy}/{MM}/{dd}/{uuid}.{ext}），元数据入 sys_file。
 * 读取：按需签发限时预签名 URL（方案 B），不暴露桶直链。
 * MinIO 不可用时直接报错（不降级本地磁盘）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    /** 单个文件上限 10MB（API 12.1） */
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    @Value("${app.minio.endpoint:http://192.168.114.100:9000}")
    private String minioEndpoint;
    @Value("${app.minio.access-key:minioadmin}")
    private String minioAccessKey;
    @Value("${app.minio.secret-key:minioadmin}")
    private String minioSecretKey;
    @Value("${app.minio.bucket:hotline12345}")
    private String minioBucket;
    @Value("${app.minio.presign-expire-seconds:7200}")
    private int presignExpireSeconds;

    /** MinIO 客户端（懒初始化，避免连接信息未配置时启动失败） */
    private volatile MinioClient minioClient;

    private MinioClient minio() {
        if (minioClient == null) {
            synchronized (this) {
                if (minioClient == null) {
                    minioClient = MinioClient.builder()
                            .endpoint(minioEndpoint)
                            .credentials(minioAccessKey, minioSecretKey)
                            .build();
                }
            }
        }
        return minioClient;
    }

    /** 桶不存在时自动创建（首次上传触发） */
    private void ensureBucket() {
        try {
            boolean exists = minio().bucketExists(BucketExistsArgs.builder().bucket(minioBucket).build());
            if (!exists) {
                minio().makeBucket(MakeBucketArgs.builder().bucket(minioBucket).build());
                log.info("MinIO bucket 已创建：{}", minioBucket);
            }
        } catch (Exception e) {
            log.error("MinIO bucket 检查/创建失败", e);
            throw new BusinessException(ResultCode.SERVER_ERROR, "文件存储服务不可用，请稍后再试");
        }
    }
    /** 业务类型枚举（API 12.1） */
    private static final Set<String> BIZ_TYPES =
            Set.of("appeal_attachment", "dispose_proof", "dispose_photo", "avatar", "archive_export");
    /**
     * 扩展名白名单：仅允许常见图片/文档格式。
     * 拒绝 .html/.svg 等可内嵌脚本的类型，防止经静态资源直链触发存储型 XSS
     */
    private static final Set<String> ALLOWED_EXTS =
            Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "pdf", "doc", "docx", "xls", "xlsx");

    private final SysFileMapper sysFileMapper;
    private final OrderDisposeAttachmentMapper disposeAttachmentMapper;

    /**
     * 通用文件上传（API 12.1）：返回 {file_id, file_name, file_url, file_size, file_type, upload_time}
     */
    public Map<String, Object> upload(MultipartFile file, String bizType, Long orderId, Long uploadUserId) {
        // 上传文件
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传文件不能为空");
        }
        if (!BIZ_TYPES.contains(bizType)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "业务类型biz_type非法");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件大小不能超过10MB");
        }
        String storedPath = storeToMinio(file, bizType);
        SysFile sysFile = new SysFile();
        sysFile.setBizType(bizType);
        sysFile.setOrderId(orderId);
        sysFile.setFileName(file.getOriginalFilename());
        sysFile.setFileUrl(storedPath);
        sysFile.setFileSize(file.getSize());
        sysFile.setFileType(file.getContentType());
        sysFile.setUploadUserId(uploadUserId);
        sysFile.setUploadTime(LocalDateTime.now());
        sysFileMapper.insert(sysFile);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file_id", sysFile.getId());
        data.put("file_name", sysFile.getFileName());
        data.put("file_url", sysFile.getFileUrl());
        data.put("file_size", sysFile.getFileSize());
        data.put("file_type", sysFile.getFileType());
        data.put("upload_time", sysFile.getUploadTime());
        return data;
    }

    /**
     * 处置凭证附件上传（API 11.1.2）：附件类型 CREDENTIAL（凭证）/SCENE_PHOTO（现场照片），
     * 落盘复用通用存储逻辑，记录入 order_dispose_attachment（最多5个，单个≤10MB）
     */
    public Map<String, Object> uploadDisposeAttachment(MultipartFile file, String attachmentType,
                                                       Long orderId, Long uploadUserId) {
        // 上传DisposeAttachment
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "附件文件不能为空");
        }
        if (!"CREDENTIAL".equals(attachmentType) && !"SCENE_PHOTO".equals(attachmentType)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "附件类型必须为CREDENTIAL/SCENE_PHOTO之一");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "附件大小不能超过10MB");
        }
        if (disposeAttachmentMapper.countByOrderId(orderId) >= 5) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "附件数量已达上限（最多5个）");
        }
        String bizType = "CREDENTIAL".equals(attachmentType) ? "dispose_proof" : "dispose_photo";
        String storedPath = storeToMinio(file, bizType);
        OrderDisposeAttachment attachment = new OrderDisposeAttachment();
        attachment.setOrderId(orderId);
        attachment.setFileName(file.getOriginalFilename());
        attachment.setFileUrl(storedPath);
        attachment.setFileSize(file.getSize());
        attachment.setFileType(file.getContentType());
        attachment.setAttachmentType(attachmentType);
        attachment.setUploadUserId(uploadUserId);
        disposeAttachmentMapper.insert(attachment);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attachment_id", attachment.getId());
        data.put("file_url", attachment.getFileUrl());
        return data;
    }

    /** 文件写入 MinIO：对象键 {biz_type}/{yyyy}/{MM}/{dd}/{uuid}.{ext}，返回对象键（sys_file.file_url） */
    private String storeToMinio(MultipartFile file, String bizType) {
        // 校验扩展名白名单（防 .html/.svg 等存储型 XSS 与伪装文件）
        try {
            LocalDate now = LocalDate.now();
            String ext = "";
            String original = file.getOriginalFilename();
            if (original != null && original.lastIndexOf('.') >= 0) {
                ext = original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            }
            if (ext.isEmpty() || !ALLOWED_EXTS.contains(ext)) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "不支持的文件类型，仅允许图片（jpg/png/gif/bmp/webp）与文档（pdf/doc/docx/xls/xlsx）");
            }
            String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            String objectKey = bizType + "/" + now.getYear() + "/"
                    + String.format("%02d", now.getMonthValue()) + "/"
                    + String.format("%02d", now.getDayOfMonth()) + "/" + name;
            ensureBucket();
            try (InputStream in = file.getInputStream()) {
                minio().putObject(PutObjectArgs.builder()
                        .bucket(minioBucket)
                        .object(objectKey)
                        .stream(in, file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build());
            }
            return objectKey;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // MinIO 不可用直接报错（需求：不降级本地磁盘）；原始异常仅入日志
            log.error("文件存储失败（MinIO）", e);
            throw new BusinessException(ResultCode.SERVER_ERROR, "文件存储服务不可用，请稍后再试");
        }
    }

    /** 按 file_id 签发预签名 URL（供前端动态取限时直链） */
    public Map<String, Object> presignedUrlOf(Long fileId) {
        SysFile sysFile = sysFileMapper.selectById(fileId);
        if (sysFile == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "文件不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file_id", sysFile.getId());
        data.put("file_name", sysFile.getFileName());
        data.put("url", presignedUrl(sysFile.getFileUrl()));
        return data;
    }
    /**
     * 诉求附件列表（市民提交时上传，MinIO 预签名下载链接）：
     * 附件在提交时绑定到父诉求（sys_file.appeal_id），父/子诉求查看共用（子诉求展示其父诉求附件）
     */
    public List<Map<String, Object>> listByAppeal(Long appealId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysFile f : sysFileMapper.selectByAppealId(appealId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file_id", f.getId());
            m.put("file_name", f.getFileName());
            m.put("file_type", f.getFileType());
            m.put("file_size", f.getFileSize());
            m.put("url", presignedUrl(f.getFileUrl()));
            list.add(m);
        }
        return list;
    }

    /** 附件访问 URL（局域网部署方案）：桶已公开读，返回同源相对路径 /minio/{bucket}/{objectKey}，
     *  经 nginx 反代到 MinIO——任意可达地址的同事浏览器都能直接预览（无需签名/无需直连 MinIO 端口）。 */
    public String presignedUrl(String storedUrl) {
        if (storedUrl == null || storedUrl.isBlank()
                || storedUrl.startsWith("/uploads/") || storedUrl.startsWith("uploads/")
                || storedUrl.startsWith("/minio/")) {
            return storedUrl;
        }
        return "/minio/" + minioBucket + "/" + storedUrl;
    }

    /** 文件内容与元数据（流式读取附件，供 /common/file/content 端点使用） */
    public record FileContent(java.io.InputStream in, String fileName, String contentType, long size) {}

    /**
     * 按 file_id 或对象键读取文件内容（附件查看/下载）：
     * fileId 优先（sys_file 元数据，含原始文件名/类型/大小）；
     * url 传存储对象键（兼容处置凭证等无 file_id 场景），文件名/类型按对象键推断。
     * 返回流由调用方负责关闭。
     */
    public FileContent contentOf(Long fileId, String url) {
        String objectKey;
        String fileName;
        String contentType;
        long size;
        if (fileId != null) {
            SysFile f = sysFileMapper.selectById(fileId);
            if (f == null) {
                throw new BusinessException(ResultCode.DATA_NOT_FOUND, "文件不存在");
            }
            objectKey = f.getFileUrl();
            fileName = f.getFileName() == null || f.getFileName().isBlank() ? objectKey : f.getFileName();
            contentType = f.getFileType();
            size = f.getFileSize() == null ? -1 : f.getFileSize();
        } else {
            objectKey = normalizeObjectKey(url);
            if (objectKey == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "文件地址非法");
            }
            int slash = objectKey.lastIndexOf('/');
            fileName = slash >= 0 ? objectKey.substring(slash + 1) : objectKey;
            contentType = guessContentType(fileName);
            size = -1;
        }
        try {
            InputStream in = minio().getObject(GetObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(objectKey)
                    .build());
            return new FileContent(in, fileName, contentType, size);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("文件读取失败（MinIO）objectKey={}", objectKey, e);
            throw new BusinessException(ResultCode.SERVER_ERROR, "文件读取失败，请稍后再试");
        }
    }

    /** 归一化对象键：兼容存储相对键（appeal_attachment/2026/...）与 /minio/{bucket}/ 同源路径；非法返回 null */
    private String normalizeObjectKey(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String key = url.trim();
        String prefix = "/minio/" + minioBucket + "/";
        if (key.startsWith(prefix)) {
            key = key.substring(prefix.length());
        }
        // 对象键格式：{bizType}/{yyyy}/{MM}/{dd}/{uuid}.{ext}，防路径穿越/越权读取
        if (key.startsWith("/") || key.contains("..") || key.contains("\\")) {
            return null;
        }
        String[] parts = key.split("/");
        if (parts.length != 5 || !BIZ_TYPES.contains(parts[0])
                || parts[1].length() != 4 || !parts[1].matches("\\d{4}")
                || !parts[2].matches("\\d{2}") || !parts[3].matches("\\d{2}")) {
            return null;
        }
        return key;
    }

    /** 按扩展名推断 MIME（无元数据时使用，仅允许白名单内格式） */
    private String guessContentType(String fileName) {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" ->
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/octet-stream";
        };
    }
}