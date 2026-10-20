package org.tjetc.backend.dto.staff;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 街道办提交处置结果（API 9.4）
 */
public record DisposeRequest(
        @NotBlank(message = "处置方案不能为空")
        @Size(max = 2000, message = "处置方案不能超过2000字")
        @JsonProperty("dispose_plan")
        String disposePlan,

        @NotBlank(message = "处置结果不能为空")
        @Size(max = 2000, message = "处置结果不能超过2000字")
        @JsonProperty("dispose_result")
        String disposeResult,

        /** 处置标签：RESOLVED/PART_RESOLVED/UNRESOLVED/TRANSFERRED（API 14.1） */
        @NotBlank(message = "处置标签不能为空")
        @Pattern(regexp = "^(RESOLVED|PART_RESOLVED|UNRESOLVED|TRANSFERRED)$",
                message = "处置标签必须为RESOLVED/PART_RESOLVED/UNRESOLVED/TRANSFERRED之一")
        @JsonProperty("dispose_tag")
        String disposeTag,

        /** 实际处置时间（ISO 8601） */
        @NotBlank(message = "实际处置时间不能为空")
        @JsonProperty("dispose_time")
        String disposeTime,

        @NotBlank(message = "处置人员不能为空")
        @JsonProperty("dispose_user_name")
        String disposeUserName,

        /** 处置凭证/现场照片附件 ID 列表（API 11.1.2 上传所得） */
        @JsonProperty("attachment_ids")
        List<Long> attachmentIds) {
}
