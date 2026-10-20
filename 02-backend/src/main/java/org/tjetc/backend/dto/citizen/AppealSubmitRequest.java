package org.tjetc.backend.dto.citizen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 市民提交诉求（API 4.1）：诉求内容 + 类别 + 分级区划 + 详细地址 + 宽泛诉求开关 + 附件
 * 标题由管理端收到诉求后 AI 总结生成（不再由市民填写）
 */
public record AppealSubmitRequest(
        @NotBlank(message = "诉求内容不能为空")
        String content,

        /** 诉求类别（8大类 code 或 OTHER，市民提交自带） */
        String category,

        @NotBlank(message = "省级行政区划不能为空")
        String province,

        @NotBlank(message = "市级行政区划不能为空")
        String city,

        /** 区级行政区划（全市范围性诉求时为选填） */
        String district,

        /** 街道（乡镇） */
        String street,

        /** 社区（选填） */
        String community,

        /** 详细地址补充（点位/楼栋/路段等，选填） */
        @JsonProperty("address_detail")
        String addressDetail,

        /** 宽泛诉求开关，默认 false */
        @JsonProperty("broad_flag")
        Boolean broadFlag,

        /** 已上传附件 ID 列表（P2 附件上传接入前为空，不参与主流程） */
        @JsonProperty("attachment_ids")
        List<Long> attachmentIds) {
}