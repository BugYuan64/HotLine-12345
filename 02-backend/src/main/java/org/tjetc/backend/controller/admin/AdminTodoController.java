package org.tjetc.backend.controller.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.service.admin.TodoSummaryService;

import java.util.Map;

/**
 * 管理端首页"待我介入"待办聚合接口：
 * 一次性返回待重分工单/待分派/待终审/待审核预警/紧急预警超时/未处理敏感事件数量，
 * 供管理端 Dashboard 待办卡片展示与跳转。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
public class AdminTodoController {

    private final TodoSummaryService todoSummaryService;

    /** 待办聚合概览：各待处理事项计数 */
    @GetMapping("/todo/summary")
    public Result<Map<String, Object>> summary() {
        // 数据汇总
        return Result.ok(todoSummaryService.summary());
    }
}