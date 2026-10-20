package org.tjetc.backend.controller.citizen;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.citizen.EvaluateRequest;
import org.tjetc.backend.dto.citizen.ReopenApplyRequest;
import org.tjetc.backend.service.citizen.PublicFeedbackService;

import java.util.Map;

/**
 * 公众端工单反馈接口（API 11.4 章）：
 * 处置结果查看 / 满意度评价 / 重开申请 / 工单流转时间线
 * 身份核验：诉求编号 + 预留手机号双重匹配（API 4.4 技术说明）
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/public/appeal")
public class PublicFeedbackController {

    private final PublicFeedbackService feedbackService;

    /** 处置结果查看（API 11.4.1）：展示脱敏处置信息，不展示处置人员姓名与内部备注；登录市民可免传 phone */
    @GetMapping("/{appealNo}/result")
    public Result<Map<String, Object>> result(@PathVariable String appealNo,
                                              @RequestParam(name = "child_appeal_id", required = false) String childAppealId,
                                              @RequestParam(required = false) String phone) {
        // 执行result操作
        return Result.ok(feedbackService.result(appealNo, childAppealId, phone, UserContext.get()));
    }

    /** 满意度评价（API 11.4.2）：处置完成后7天内评价一次（可指定子诉求单独评价），提交后不可修改 */
    @PostMapping("/{appealNo}/evaluate")
    public Result<Map<String, Object>> evaluate(@PathVariable String appealNo,
                                                @Valid @RequestBody EvaluateRequest request) {
        // 评价工单
        return Result.ok(feedbackService.evaluate(appealNo, request, UserContext.get()));
    }

    /** 重开申请（API 11.4.3）：不满意/非常不满意可申请（可指定子诉求），最多重开1次 */
    @PostMapping("/{appealNo}/reopen")
    public Result<Map<String, Object>> reopen(@PathVariable String appealNo,
                                              @Valid @RequestBody ReopenApplyRequest request) {
        // 重开工单
        return Result.ok(feedbackService.reopen(appealNo, request.childAppealId(),
                request.phone(), request.reopenReason(), UserContext.get()));
    }

    /** 工单流转时间线（API 11.4.4）：节点显示时间与操作方角色，不暴露具体人员姓名；登录市民可免传 phone */
    @GetMapping("/{appealNo}/timeline")
    public Result<Map<String, Object>> timeline(@PathVariable String appealNo,
                                                @RequestParam(required = false) String phone) {
        // 执行timeline操作
        return Result.ok(feedbackService.timeline(appealNo, phone, UserContext.get()));
    }

    /** 诉求级流程进度（区别于工单时间线）：管理端处理中 → 部门端处理中(具体部门) → 街道办处理中(具体街道办) → 已办结；登录市民可免传 phone */
    @GetMapping("/{appealNo}/stage")
    public Result<Map<String, Object>> stage(@PathVariable String appealNo,
                                             @RequestParam(required = false) String phone) {
        return Result.ok(feedbackService.stage(appealNo, phone, UserContext.get()));
    }
}