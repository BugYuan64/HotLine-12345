package org.tjetc.backend.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.admin.RoleMenuConfigRequest;
import org.tjetc.backend.service.admin.RoleService;

import java.util.Map;

/**
 * 角色与权限管理接口（API 5.8/5.10）：
 * 角色列表、菜单列表、角色权限配置（sys_role_menu）（仅 ADMIN 可访问）
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
public class AdminRoleController {

    private final RoleService roleService;

    /** 角色列表查询（API 5.8） */
    @GetMapping("/roles")
    public Result<Map<String, Object>> roles() {
        // 执行roles操作
        return Result.ok(roleService.list());
    }

    /** 菜单列表（角色权限配置界面数据源） */
    @GetMapping("/menus")
    public Result<Map<String, Object>> menus() {
        // 执行menus操作
        return Result.ok(roleService.menus());
    }

    /** 角色已配置菜单（GET 对应 API 5.10） */
    @GetMapping("/roles/{id}/menus")
    public Result<Map<String, Object>> roleMenus(@PathVariable Long id) {
        // 执行roleMenus操作
        return Result.ok(roleService.getMenus(id));
    }

    /** 角色权限配置（API 5.10） */
    @PutMapping("/roles/{id}/menus")
    public Result<Map<String, Object>> assignMenus(@PathVariable Long id,
                                                   @Valid @RequestBody RoleMenuConfigRequest request) {
        // 分配Menus
        return Result.ok(roleService.assignMenus(id, request, UserContext.get()));
    }
}