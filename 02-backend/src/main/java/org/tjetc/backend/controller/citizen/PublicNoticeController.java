package org.tjetc.backend.controller.citizen;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.entity.common.Notice;
import org.tjetc.backend.service.common.NoticeService;

import java.util.List;
import java.util.Map;

/**
 * 公众端通知公告接口（市民端首页"通知公告"模块）：
 * - GET /api/v1/public/notices?page=&size=  全部公告（分页）
 * - GET /api/v1/public/notices/top?limit=   首页顶部公告（默认 3 条）
 * - GET /api/v1/public/notices/{id}        公告详情
 *
 * 设计要点：
 * 1. 仅返回 status=1 展示中的公告，下架/草稿不返回
 * 2. 公众端不暴露管理字段（如 updateTime 仅作为排序参考，不返回）
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/public/notices")
public class PublicNoticeController {

    private final NoticeService noticeService;

    /** 全部公告分页（keyword 标题/正文/摘要模糊查询） */
    @GetMapping
    public Result<Map<String, Object>> page(@RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 10;
        return Result.ok(noticeService.publicPage(keyword, page, size).toMap());
    }

    /** 首页顶部公告（默认 3 条，最多不超过 10） */
    @GetMapping("/top")
    public Result<List<Notice>> top(@RequestParam(defaultValue = "3") int limit) {
        if (limit < 1 || limit > 10) limit = 3;
        return Result.ok(noticeService.publicTop(limit));
    }

    /** 公告详情 */
    @GetMapping("/{id}")
    public Result<Notice> detail(@PathVariable Long id) {
        return Result.ok(noticeService.publicDetail(id));
    }
}
