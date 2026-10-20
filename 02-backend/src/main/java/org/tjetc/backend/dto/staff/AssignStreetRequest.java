package org.tjetc.backend.dto.staff;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 分派至街道办（API 9.3）：缺省则按工单行政区划自动匹配
 */
public record AssignStreetRequest(
        /** 承办街道办（手动调整时传，缺省自动匹配） */
        @JsonProperty("street_office")
        String streetOffice,
        /** 分派说明（选填）：随分派动作写入流转日志 */
        @JsonProperty("remark")
        String remark) {
}
