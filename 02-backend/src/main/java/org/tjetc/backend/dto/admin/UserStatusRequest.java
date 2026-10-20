package org.tjetc.backend.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 账号状态切换（API 5.6）：启用/停用，与审核状态相互独立
 */
public record UserStatusRequest(
        @NotBlank(message = "目标状态不能为空")
        @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "目标状态必须为ENABLED/DISABLED之一")
        String status) {
}