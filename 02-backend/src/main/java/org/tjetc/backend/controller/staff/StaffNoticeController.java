package org.tjetc.backend.controller.staff;

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
 * 部门端通知公告接口（通知公告模块，/api/v1/staff/notices，仅 8 大部门账号可访问）：
 * - Tab1 收到的通知（管理员/其他部门名义发给我部门的，发布者+日期筛选，含逐条已读）
 * - Tab2 我发布的（本部门名义全部通知，含管理员代发；编辑/下架）
 * - 发布（层级限制：仅街道办 + 市民公开，后端强制本部门名义）
 * 与街道办端在 Controller 层物理隔离。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/staff/notices")
public class StaffNoticeController {

    private final NoticeService noticeService;

    /** Tab1：收到的通知（发给我部门的展示中通知，含逐条已读标记；参数名 snake_case 与前端一致） */
    @GetMapping("/received")
    public Result<Map<String, Object>> received(@RequestParam(name = "publisher_type", required = false) String publisherType,
                                                @RequestParam(name = "publisher_dept", required = false) String publisherDept,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(name = "start_date", required = false) String startDate,
                                                @RequestParam(name = "end_date", required = false) String endDate,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        // 查询收到的通知
        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 10;
        return Result.ok(noticeService.deptReceived(UserContext.get(), publisherType, publisherDept, keyword, startDate, endDate, page, size).toMap());
    }

    /** Tab2：我发布的（本部门名义全部通知，含管理员代发；关键词/日期区间筛选） */
    @GetMapping("/published")
    public Result<Map<String, Object>> published(@RequestParam(required = false) String keyword,
                                                 @RequestParam(name = "start_date", required = false) String startDate,
                                                 @RequestParam(name = "end_date", required = false) String endDate,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        // 查询我发布的
        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 10;
        return Result.ok(noticeService.deptPublished(UserContext.get(), keyword, startDate, endDate, page, size).toMap());
    }

    /** 通知详情（校验发给我部门；点开自动记已读） */
    @GetMapping("/{id}")
    public Result<NoticeVO> detail(@PathVariable Long id) {
        // 查询详情并记已读
        return Result.ok(noticeService.deptDetail(UserContext.get(), id));
    }

    /** 发布通知（仅街道办 + 市民公开，名义固定本部门） */
    @PostMapping
    public Result<NoticeVO> create(@Valid @RequestBody NoticeUpsertRequest request) {
        // 发布通知
        return Result.ok(noticeService.deptCreate(UserContext.get(), request));
    }

    /** 编辑通知（仅本部门名下；名义发布者不可改） */
    @PutMapping("/{id}")
    public Result<NoticeVO> update(@PathVariable Long id, @Valid @RequestBody NoticeUpsertRequest request) {
        // 编辑通知
        return Result.ok(noticeService.deptUpdate(UserContext.get(), id, request));
    }

    /** 下架通知（仅本部门名下，status=0） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        // 下架通知
        noticeService.deptOffShelf(UserContext.get(), id);
        return Result.ok();
    }

    /** 重新发布已下架通知（仅本部门名下；可改内容/接收对象；发布时间刷新，清空已读全员重新提醒） */
    @PutMapping("/{id}/republish")
    public Result<NoticeVO> republish(@PathVariable Long id, @Valid @RequestBody NoticeUpsertRequest request) {
        // 重新发布
        return Result.ok(noticeService.deptRepublish(UserContext.get(), id, request));
    }
}
