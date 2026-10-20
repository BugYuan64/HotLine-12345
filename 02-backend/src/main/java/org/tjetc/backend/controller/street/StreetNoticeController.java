package org.tjetc.backend.controller.street;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.NoticeUpsertRequest;
import org.tjetc.backend.dto.NoticeVO;
import org.tjetc.backend.service.common.NoticeService;

import java.util.Map;

/**
 * 街道办端通知公告接口（通知公告模块，/api/v1/street/notices，仅 STREET 账号可访问）：
 * 接收上级通知（管理员/部门名义发给我街道办）+ 本街道发布（仅市民公开 PUBLIC，层级限制）；
 * 发布/编辑/下架/重发操作写街道办端审计日志。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/street/notices")
public class StreetNoticeController {

    private final NoticeService noticeService;

    /** 收到的通知列表（发给我街道办的展示中通知，含已读标记；关键词模糊搜索） */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(name = "publisher_type", required = false) String publisherType,
                                            @RequestParam(name = "publisher_dept", required = false) String publisherDept,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(name = "start_date", required = false) String startDate,
                                            @RequestParam(name = "end_date", required = false) String endDate,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 10;
        return Result.ok(noticeService.streetReceived(UserContext.get(), publisherType, publisherDept, keyword, startDate, endDate, page, size).toMap());
    }

    /** 我发布的（本街道名义，仅市民公开；关键词模糊搜索） */
    @GetMapping("/published")
    public Result<Map<String, Object>> published(@RequestParam(required = false) String keyword,
                                                 @RequestParam(name = "start_date", required = false) String startDate,
                                                 @RequestParam(name = "end_date", required = false) String endDate,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 10;
        return Result.ok(noticeService.streetPublished(UserContext.get(), keyword, startDate, endDate, page, size).toMap());
    }

    /** 发布（层级限制：仅市民公开 PUBLIC，名义固定本街道） */
    @PostMapping
    public Result<NoticeVO> create(@Valid @RequestBody NoticeUpsertRequest request) {
        return Result.ok(noticeService.streetCreate(UserContext.get(), request));
    }

    /** 编辑（仅本街道名下；已读保留，接收对象差异同步） */
    @PutMapping("/{id}")
    public Result<NoticeVO> update(@PathVariable Long id, @Valid @RequestBody NoticeUpsertRequest request) {
        return Result.ok(noticeService.streetUpdate(UserContext.get(), id, request));
    }

    /** 下架（仅本街道名下，status=0） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        noticeService.streetOffShelf(UserContext.get(), id);
        return Result.ok(null);
    }

    /** 重新发布已下架通知（仅本街道名下；重建收件箱，已读清零重新提醒） */
    @PutMapping("/{id}/republish")
    public Result<NoticeVO> republish(@PathVariable Long id, @Valid @RequestBody NoticeUpsertRequest request) {
        return Result.ok(noticeService.streetRepublish(UserContext.get(), id, request));
    }

    /** 通知详情（校验发给我街道办；点开自动记已读） */
    @GetMapping("/{id}")
    public Result<NoticeVO> detail(@PathVariable Long id) {
        return Result.ok(noticeService.streetDetail(UserContext.get(), id));
    }
}
