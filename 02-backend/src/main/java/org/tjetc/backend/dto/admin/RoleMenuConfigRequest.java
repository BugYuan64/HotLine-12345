package org.tjetc.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 角色权限配置（API 5.10）：配置角色对应的菜单/操作权限（sys_role_menu）
 */
public record RoleMenuConfigRequest(
        @NotNull(message = "菜单/权限ID列表不能为空")
        @JsonProperty("menu_ids")
        List<Long> menuIds) {
}