package org.tjetc.backend.controller.common;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.service.admin.ClassifyService;
import org.tjetc.backend.service.admin.FileService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 通用文件服务（API 12.1）：跨角色统一上传，一次上传、多处引用
 * 所有已登录角色均可调用（市民/街道办/部门/Admin，AuthInterceptor 已放行 /common/**）
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/common")
public class CommonController {

    private final FileService fileService;
    private final ClassifyService classifyService;

    /** 一级分类列表（分类下拉/展示数据源，所有已登录角色可用） */
    @GetMapping("/classify/primaries")
    public Result<List<Map<String, Object>>> primaries() {
        return Result.ok(classifyService.listPrimaries());
    }

    /**
     * 通用文件上传（API 12.1）：单个≤10MB，biz_type 见 API 12.1 枚举
     */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam("biz_type") String bizType,
                                              @RequestParam(name = "order_id", required = false) Long orderId) {
        // 上传文件
        Long userId = UserContext.get() == null ? null : UserContext.get().userId();
        return Result.ok(fileService.upload(file, bizType, orderId, userId));
    }

    /** 取文件限时预签名直链（方案 B：元数据在库、文件在 MinIO，前端按需换取临时访问链接） */
    @GetMapping("/files/{fileId}/url")
    public Result<Map<String, Object>> fileUrl(@org.springframework.web.bind.annotation.PathVariable Long fileId) {
        return Result.ok(fileService.presignedUrlOf(fileId));
    }

    /**
     * 附件内容流式读取（图片内联预览 / 文件附件下载）：
     * 解决 /minio 相对路径在无 nginx 反代环境（Vite 开发代理）下 404 的问题，
     * 前端统一改走本端点：fileId 优先，url 兼容处置凭证等无元数据的对象键。
     * 认证：任意已登录角色，前端 <img>/下载链接通过 ?token= 携带（AuthInterceptor 已支持 query token）。
     */
    @GetMapping("/file/content")
    public void fileContent(@RequestParam(name = "fileId", required = false) Long fileId,
                            @RequestParam(name = "url", required = false) String url,
                            HttpServletResponse response) throws IOException {
        if (fileId == null && (url == null || url.isBlank())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "fileId 或 url 至少传一个");
        }
        FileService.FileContent fc = fileService.contentOf(fileId, url);
        String contentType = fc.contentType() == null || fc.contentType().isBlank()
                ? "application/octet-stream" : fc.contentType();
        // 图片内联预览，其余文件附件直接下载（浏览器按 Content-Disposition 处理）
        boolean image = contentType.startsWith("image/");
        String encoded = URLEncoder.encode(fc.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(contentType);
        response.setHeader("Content-Disposition",
                (image ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded + "; filename=\"" + encoded + "\"");
        if (fc.size() > 0) {
            response.setContentLengthLong(fc.size());
        }
        try (InputStream in = fc.in(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException e) {
            // 客户端中断（关闭预览/取消下载）属正常现象，仅记录 debug
            org.slf4j.LoggerFactory.getLogger(CommonController.class).debug("附件流输出中断", e);
        }
    }
}