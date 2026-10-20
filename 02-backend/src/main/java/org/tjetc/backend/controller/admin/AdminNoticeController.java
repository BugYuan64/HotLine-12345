package org.tjetc.backend.controller.admin;

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
import org.tjetc.backend.dto.NoticeUpsertRequest;
import org.tjetc.backend.dto.NoticeVO;
import org.tjetc.backend.service.common.NoticeService;

import java.util.Map;

/**
 * 管理端通知公告接口（通知公告模块，/api/v1/admin/notices，仅 ADMIN 角色可访问）：
 * - 列表分页（标题/状态/发布者/日期筛选）/ 新增 / 编辑 / 下架 / 详情
 * - 发布者可选管理员本人或八大部门名义；接收对象部门/街道办/市民公开灵活多选
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/notices")
public class AdminNoticeController {

    private final NoticeService noticeService;

    /** 发布弹窗下拉数据：八大部门 + 全部街道办 */
    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        return Result.ok(noticeService.adminOptions());
    }

    /** 分页查询（标题/关键词模糊 + 状态 + 发布者 + 日期区间筛选，参数名 snake_case 与前端一致） */
    @GetMapping
    public Result<Map<String, Object>> page(@RequestParam(required = false) String title,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(name = "publisher_type", required = false) String publisherType,
                                            @RequestParam(name = "publisher_dept", required = false) String publisherDept,
                                            @RequestParam(name = "start_date", required = false) String startDate,
                                            @RequestParam(name = "end_date", required = false) String endDate,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        // 查询通知列表
        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 10;
        return Result.ok(noticeService.adminPage(title, keyword, status, publisherType, publisherDept, startDate, endDate, page, size).toMap());
    }

    /** 通知详情（含接收对象明细，编辑回填用） */
    @GetMapping("/{id}")
    public Result<NoticeVO> detail(@PathVariable Long id) {
        // 查询通知详情
        return Result.ok(noticeService.adminDetail(id));
    }

    /** 新增通知（发布者 + 接收对象多选） */
    @PostMapping
    public Result<NoticeVO> create(@Valid @RequestBody NoticeUpsertRequest request) {
        // 发布通知
        return Result.ok(noticeService.adminCreate(request));
    }

    /** 修改通知（标题/正文/接收对象可改，名义发布者不可改） */
    @PutMapping("/{id}")
    public Result<NoticeVO> update(@PathVariable Long id, @Valid @RequestBody NoticeUpsertRequest request) {
        // 编辑通知
        return Result.ok(noticeService.adminUpdate(id, request));
    }

    /** 下架通知（status=0，不物理删除） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        // 下架通知
        noticeService.adminOffShelf(id);
        return Result.ok();
    }

    /** 重新发布已下架通知（可改内容/接收对象；发布时间刷新，清空已读全员重新提醒） */
    @PutMapping("/{id}/republish")
    public Result<NoticeVO> republish(@PathVariable Long id, @Valid @RequestBody NoticeUpsertRequest request) {
        // 重新发布
        return Result.ok(noticeService.adminRepublish(id, request));
    }
}
