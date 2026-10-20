package org.tjetc.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 通知发布/编辑请求（通知公告模块，管理端/部门端共用）：
 * - 管理端：publisher_type 可选 ADMIN/DEPT（部门名义需同时传 publisher_dept）
 * - 部门端：后端强制 publisher_type=DEPT 且 publisher_dept=本部门，请求值忽略
 * - targets 接收对象：depts 部门code列表 / streets 街道办名列表 / public 是否对市民公开，至少一项
 */
public record NoticeUpsertRequest(
        @JsonProperty("title")
        String title,

        @JsonProperty("content")
        String content,

        @JsonProperty("publisher_type")
        String publisherType,

        @JsonProperty("publisher_dept")
        String publisherDept,

        @JsonProperty("targets")
        Targets targets) {

    /** 接收对象（多选灵活组合） */
    public record Targets(
            @JsonProperty("depts")
            List<String> depts,

            @JsonProperty("streets")
            List<String> streets,

            @JsonProperty("public")
            Boolean publicFlag) {
    }
}
