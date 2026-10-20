package org.tjetc.backend.controller.staff;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.service.dept.DeptOrderService;

import java.util.Map;

/**
 * 部门端诉求趋势接口（部门工作台「本部门诉求趋势」折线图）——三端数据解耦：
 * 只读部门端副本表 dept_work_order；一级分类由登录部门的 JWT user_type 强制指定
 * （即本部门所属分类），前端传参无法越权查看其他分类；
 * 仅开放二级分类（含「全部」= 本部门一级总括）与时间窗口两个筛选维度
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/staff")
public class StaffTrendController {

    private final DeptOrderService deptOrderService;

    /**
     * 本部门每日诉求趋势（工作台折线图数据源）
     *
     * @param days        近 N 天（7/15/30，默认 15）
     * @param subCategory 二级分类名（不传 = 本部门一级分类总括）
     * @param region      区县名（不传 = 不过滤；按前缀匹配只筛到区级）
     */
    @GetMapping("/trends/daily")
    public Result<Map<String, Object>> dailyTrend(@RequestParam(defaultValue = "15") Integer days,
                                                  @RequestParam(required = false) String subCategory,
                                                  @RequestParam(required = false) String region) {
        // 一级分类锁定为登录部门 user_type（URBAN/TRAFFIC/…），数据范围天然限定本部门副本表
        return Result.ok(deptOrderService.dailyTrend(days, subCategory, region, UserContext.get()));
    }
}
