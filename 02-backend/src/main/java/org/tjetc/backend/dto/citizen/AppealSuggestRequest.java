package org.tjetc.backend.dto.citizen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 智能诉求引导与补全请求（API 4.2）：市民填写诉求时的草稿内容
 */
public record AppealSuggestRequest(
        @NotBlank(message = "诉求草稿内容不能为空")
        @Size(max = 2000, message = "诉求草稿内容不能超过2000字")
        @JsonProperty("partial_content")
        String partialContent) {
}
