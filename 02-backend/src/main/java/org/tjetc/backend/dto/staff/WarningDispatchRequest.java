package org.tjetc.backend.dto.staff;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 部门端分发预警到街道办请求（街道/乡镇名列表，与街道办账号 region 同口径）
 */
public record WarningDispatchRequest(
        @NotEmpty(message = "请选择至少一个街道/乡镇")
        @JsonProperty("street_offices")
        List<String> streetOffices) {
}