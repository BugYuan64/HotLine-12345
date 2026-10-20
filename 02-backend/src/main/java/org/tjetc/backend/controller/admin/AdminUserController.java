package org.tjetc.backend.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.admin.UserCreateRequest;
import org.tjetc.backend.dto.admin.UserRoleAssignRequest;
import org.tjetc.backend.dto.admin.UserStatusRequest;
import org.tjetc.backend.dto.admin.UserUpdateRequest;
import org.tjetc.backend.service.admin.UserAccountService;

import java.util.Map;

/**
 * 用户账号管理接口（API 5.2/5.6/5.9）：
 * 工作人员账号增删改查、账号状态切换（启用/停用）、用户角色分配（仅 ADMIN 可访问）
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserController {

    private final UserAccountService userAccountService;

    /** 用户账号分页查询（API 5.2 GET） */
    @GetMapping("/users")
    public Result<Map<String, Object>> users(@RequestParam(required = false) String name,
                                             @RequestParam(required = false) String job_no,
                                             @RequestParam(required = false) String user_type,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 执行users操作
        PageData<Map<String, Object>> data =
                userAccountService.page(name, job_no, user_type, status, page, pageSize);
        return Result.page(data);
    }

    /** 新增工作人员账号（API 5.2 POST） */
    @PostMapping("/users")
    public Result<Map<String, Object>> create(@Valid @RequestBody UserCreateRequest request) {
        // 创建新数据
        return Result.ok(userAccountService.create(request, UserContext.get()));
    }

    /** 编辑工作人员账号（API 5.2 PUT） */
    @PutMapping("/users/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id,
                                              @Valid @RequestBody UserUpdateRequest request) {
        // 更新数据
        return Result.ok(userAccountService.update(id, request, UserContext.get()));
    }

    /** 删除工作人员账号（API 5.2 DELETE）：逻辑删除 */
    @DeleteMapping("/users/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Long id) {
        // 删除数据
        return Result.ok(userAccountService.delete(id, UserContext.get()));
    }

    /** 账号状态切换（API 5.6） */
    @PostMapping("/users/{id}/status")
    public Result<Map<String, Object>> status(@PathVariable Long id,
                                              @Valid @RequestBody UserStatusRequest request) {
        // 执行status操作
        return Result.ok(userAccountService.changeStatus(id, request.status(), UserContext.get()));
    }

    /** 查询用户角色（GET 对应 API 5.9） */
    @GetMapping("/users/{id}/roles")
    public Result<Map<String, Object>> roles(@PathVariable Long id) {
        // 执行roles操作
        return Result.ok(userAccountService.getRoles(id));
    }

    /** 用户角色分配（API 5.9） */
    @PutMapping("/users/{id}/roles")
    public Result<Map<String, Object>> assignRoles(@PathVariable Long id,
                                                   @Valid @RequestBody UserRoleAssignRequest request) {
        // 分配Roles
        return Result.ok(userAccountService.assignRoles(id, request, UserContext.get()));
    }
}