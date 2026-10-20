package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 用户角色分配（API 5.9）：最小权限管理，权限调整即时生效并留痕
 */
public record UserRoleAssignRequest(
        @NotNull(message = "角色ID列表不能为空")
        @JsonProperty("role_ids")
        List<Long> roleIds) {
}