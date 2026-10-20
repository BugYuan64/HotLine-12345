package org.tjetc.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 待更新工单等级升级确认（API 6.4）：目标等级用于同步更新等级与优先级
 */
public record OrderUpgradeRequest(
        @NotBlank(message = "目标等级不能为空")
        @Pattern(regexp = "^(一级|二级|三级)$", message = "目标等级必须为一级/二级/三级之一")
        @JsonProperty("new_level")
        String newLevel) {
}