package org.tjetc.backend.controller.citizen;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.ai.AiSuggestResult;
import org.tjetc.backend.dto.citizen.AppealSubmitRequest;
import org.tjetc.backend.dto.citizen.AppealSuggestRequest;
import org.tjetc.backend.entity.citizen.AppealParent;
import org.tjetc.backend.service.admin.AppealAutoProcessService;
import org.tjetc.backend.service.admin.SensitiveEventService;
import org.tjetc.backend.service.citizen.PublicAppealService;
import org.tjetc.backend.service.common.AiServiceClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公众端诉求提交接口（API 4.1/4.2）：
 * 市民提交诉求、智能诉求引导与补全建议
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/public/appeal")
public class PublicAppealController {

    private final PublicAppealService appealService;
    private final AiServiceClient aiServiceClient;
    private final AppealAutoProcessService appealAutoProcessService;
    private final SensitiveEventService sensitiveEventService;

    /** 提交诉求（API 4.1）：落库后 3 秒自动生成 AI 标题（每条诉求独立计时，不受开关影响；失败由定时兜底重试） */
    @PostMapping
    public Result<Map<String, Object>> submit(@Valid @RequestBody AppealSubmitRequest request) {
        Map<String, Object> data = appealService.submit(request, UserContext.get());
        // 出现后 3 秒自动生成标题（后端计时，单条）
        Object newId = data.get("id");
        if (newId instanceof Number) {
            appealAutoProcessService.scheduleParentTitle(((Number) newId).longValue());
        }
        return Result.ok(data);
    }

    /**
     * 智能诉求引导与补全（API 4.2）：转发 Python AI 服务 /ai/v1/suggest，
     * 返回 { suggested_title, completion, missing_elements }；AI 不可用时降级返回 50002（前端静默处理）
     */
    @PostMapping("/suggest")
    public Result<Map<String, Object>> suggest(@Valid @RequestBody AppealSuggestRequest request) {
        // 调用 AI 服务生成补全建议
        java.util.Optional<AiSuggestResult> ai = aiServiceClient.suggest(request.partialContent());
        if (ai.isEmpty()) {
            // AI 服务不可用：按文档约定降级返回 50002，前端静默处理不打扰填写
            return Result.fail(ResultCode.AI_SERVICE_ERROR, ResultCode.AI_SERVICE_ERROR.getMessage());
        }
        AiSuggestResult r = ai.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("suggested_title", r.suggestedTitle());
        data.put("completion", r.completion());
        data.put("missing_elements", r.missingElements());
        return Result.ok(data);
    }

    /**
     * 当前账号诉求列表（市民端首页"办理进度" / "查看全部诉求"）
     * GET /api/v1/public/appeal/list?page=1&size=10&appealNo=APxxx
     * appealNo 可选，用于列表页按诉求编号精确查询；返回分页数据：{ list, total, page, page_size, total_pages }
     */
    @GetMapping("/list")
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size,
                                            @RequestParam(required = false) String appealNo) {
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 10;
        String no = (appealNo == null || appealNo.isBlank()) ? null : appealNo.trim();
        return Result.ok(appealService.listByCurrentUser(UserContext.get(), page, size, no).toMap());
    }

    /**
     * 市民删除诉求（软删除）：仅当全部子诉求工单已评价、或街道办处置完成超过 7 天未评价（超评价窗口）时允许；
     * 父/子诉求一并置 is_deleted=1，数据仅留存库中，市民端列表/查询不再展示
     * DELETE /api/v1/public/appeal/{appealNo}
     */
    @DeleteMapping("/{appealNo}")
    public Result<Map<String, Object>> delete(@PathVariable String appealNo) {
        return Result.ok(appealService.deleteByCurrentUser(appealNo.trim(), UserContext.get()));
    }

    /**
     * 当前账号敏感事件列表（市民端"办理进度"：敏感剥离诉求快照的进度查看）
     * GET /api/v1/public/appeal/sensitive/list?page=1&size=10&appealNo=SExxx
     * 返回分页数据（编号/内容/地址/提交时间/处理状态），不暴露风险类型与处置建议
     */
    @GetMapping("/sensitive/list")
    public Result<Map<String, Object>> sensitiveList(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "10") int size,
                                                     @RequestParam(required = false) String appealNo) {
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 10;
        String no = (appealNo == null || appealNo.isBlank()) ? null : appealNo.trim();
        LoginUser user = UserContext.get();
        if (user == null || user.phone() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        return Result.ok(sensitiveEventService.pageByCitizenPhone(user.phone(), page, size, no).toMap());
    }
}
