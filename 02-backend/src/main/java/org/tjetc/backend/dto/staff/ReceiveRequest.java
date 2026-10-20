package org.tjetc.backend.dto.staff;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 街道办工单接收确认（API 11.1.1）
 */
public record ReceiveRequest(
        /** 接收意见（选填） */
        @JsonProperty("accept_remark")
        String acceptRemark) {
}
