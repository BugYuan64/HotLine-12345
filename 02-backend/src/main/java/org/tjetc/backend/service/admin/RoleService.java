package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.admin.RoleMenuConfigRequest;
import org.tjetc.backend.entity.admin.SysRole;
import org.tjetc.backend.mapper.admin.SysMenuMapper;
import org.tjetc.backend.mapper.admin.SysRoleMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色与权限管理（需求 4 角色与权限分配 / API 5.8/5.10）：
 * - 角色列表查询（系统管理员/城管/物业/民生/街道办/审核/督办）
 * - 菜单列表查询（角色权限配置界面数据源）
 * - 角色权限配置（sys_role_menu），先清空再写入，调整即时生效并留痕
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final AuditLogService auditLogService;

    /** 角色列表查询（API 5.8） */
    public Map<String, Object> list() {
        // 查询列表数据
        List<Map<String, Object>> list = roleMapper.selectAll().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("code", r.getCode());
            m.put("name", r.getName());
            m.put("scope", r.getScope());
            m.put("description", r.getDescription());
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", list.size());
        return data;
    }

    /** 菜单列表（角色权限配置界面数据源） */
    public Map<String, Object> menus() {
        // 执行menus操作
        List<Map<String, Object>> list = menuMapper.selectAll().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("code", r.get("code"));
            m.put("name", r.get("name"));
            m.put("parent_id", r.get("parent_id"));
            m.put("route", r.get("route"));
            m.put("perm", r.get("perm"));
            m.put("sort_order", r.get("sort_order"));
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        return data;
    }

    /** 角色已配置菜单（GET 对应 API 5.10） */
    public Map<String, Object> getMenus(Long roleId) {
        requireRole(roleId);
        List<Long> menuIds = menuMapper.selectMenuIdsByRoleId(roleId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("menu_ids", menuIds);
        return data;
    }

    /** 角色权限配置（API 5.10）：先清空再写入 */
    @Transactional
    public Map<String, Object> assignMenus(Long roleId, RoleMenuConfigRequest req, LoginUser user) {
        // 分配Menus
        SysRole role = requireRole(roleId);
        validateMenus(req.menuIds());
        menuMapper.deleteByRoleId(roleId);
        if (!req.menuIds().isEmpty()) {
            menuMapper.insertBatch(roleId, req.menuIds());
        }
        auditLogService.recordOperation("ADMIN-" + user.jobNo(), "role-permission",
                "为角色 " + role.getName() + " 配置菜单权限：" + req.menuIds());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("menu_ids", req.menuIds());
        return data;
    }

    private SysRole requireRole(Long roleId) {
        // 执行requireRole操作
        return roleMapper.selectAll().stream()
                .filter(r -> r.getId().equals(roleId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.DATA_NOT_FOUND, "角色不存在"));
    }

    private void validateMenus(List<Long> menuIds) {
        // 校验Menus
        if (menuIds == null) {
            return;
        }
        List<Long> all = menuMapper.selectAll().stream()
                .map(m -> ((Number) m.get("id")).longValue())
                .toList();
        for (Long menuId : menuIds) {
            if (!all.contains(menuId)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "菜单ID不存在：" + menuId);
            }
        }
    }
}