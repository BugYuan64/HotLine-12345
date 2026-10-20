package org.tjetc.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员手动录入工单（API 6.5）：录入后自动执行智能分类。
 * 所属辖区拆为区县 + 街道/乡镇 + 详细地址三段（均选填），后端拼接为完整地址 region。
 */
public record OrderCreateRequest(
        @NotBlank(message = "工单标题不能为空")
        @Size(max = 200, message = "工单标题不能超过200字")
        String title,

        @NotBlank(message = "工单内容不能为空")
        String content,

        /** 区县名称（选填） */
        @Size(max = 50, message = "区县名称不能超过50字")
        String district,

        /** 街道/乡镇名称（选填） */
        @Size(max = 50, message = "街道名称不能超过50字")
        String street,

        /** 详细地址（选填） */
        @JsonProperty("address_detail")
        @Size(max = 100, message = "详细地址不能超过100字")
        String addressDetail,

        /** 工单等级：一级/二级/三级（选填，默认三级） */
        String level) {
}