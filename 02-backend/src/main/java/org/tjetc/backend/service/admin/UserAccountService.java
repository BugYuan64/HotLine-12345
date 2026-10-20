package org.tjetc.backend.service.admin;

import cn.hutool.crypto.digest.BCrypt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.admin.UserCreateRequest;
import org.tjetc.backend.dto.admin.UserRoleAssignRequest;
import org.tjetc.backend.dto.admin.UserUpdateRequest;
import org.tjetc.backend.entity.admin.SysRole;
import org.tjetc.backend.entity.common.SysRegion;
import org.tjetc.backend.entity.common.SysUser;
import org.tjetc.backend.mapper.admin.SysRoleMapper;
import org.tjetc.backend.mapper.admin.SysUserRoleMapper;
import org.tjetc.backend.mapper.common.SysUserMapper;
import org.tjetc.backend.service.citizen.AuthService;
import org.tjetc.backend.service.common.RedisService;
import org.tjetc.backend.service.common.RegionService;
import org.tjetc.backend.util.MaskUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户账号管理（需求 4 用户账号管理 / API 5.2/5.6/5.9）：
 * - 工作人员（staff）账号增删改查、启用/停用，密码 bcrypt 加密，新增默认审核通过
 * - 角色分配（sys_user_role），最小权限管理、调整即时生效并留痕
 * - 逻辑删除保留历史操作记录，手机号列表展示时脱敏
 */
@Service
@RequiredArgsConstructor
public class UserAccountService {

    /** 8 大分派部门（按「区县+分类」建号，56 = 7 区县 × 8 分类） */
    private static final Set<String> DEPT_USER_TYPES = Set.of(
            "URBAN", "TRAFFIC", "MARKET", "ENVIRONMENT", "HOUSING", "LABOR", "PUBLIC", "HEALTH");

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final AuditLogService auditLogService;
    private final RedisService redisService;
    private final RegionService regionService;

    /** 账号分页查询（API 5.2 GET）：姓名模糊、工号精确、归属类型、账号状态过滤 */
    public PageData<Map<String, Object>> page(String name, String jobNo, String userType,
                                              String status, int page, int pageSize) {
        // 分页查询
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> rows =
                userMapper.selectPage(name, jobNo, userType, status, offset, pageSize);
        long total = userMapper.countPage(name, jobNo, userType, status);
        return PageData.of(rows.stream().map(this::toVo).toList(), total, page, pageSize);
    }

    /** 新增账号（API 5.2 POST）：工号/手机号唯一性校验，密码加密，默认审核通过 */
    @Transactional
    public Map<String, Object> create(UserCreateRequest req, LoginUser user) {
        // 创建新数据
        if (userMapper.countByJobNo(req.jobNo()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该工号已存在，请核对信息");
        }
        if (req.phone() != null && !req.phone().isBlank() && userMapper.countByPhone(req.phone()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该手机号已被使用，请核对信息");
        }
        // 部门账号（8大分类）：按「区县+分类」自动拼接辖区/部门名，并写入区县级 region_code
        String region = req.region();
        String regionCode = req.regionCode();
        if (DEPT_USER_TYPES.contains(req.userType())) {
            SysRegion county = regionService.getByCode(req.regionCode());
            if (county == null || !"COUNTY".equals(county.getLevel())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "请选择正确的所属区县");
            }
            region = county.getRegionName() + AuthService.DEPT_NAME.getOrDefault(req.userType(), req.userType());
            regionCode = county.getRegionCode();
        }
        SysUser u = new SysUser();
        u.setJobNo(req.jobNo());
        u.setName(req.name());
        u.setPhone(req.phone());
        u.setPassword(BCrypt.hashpw(req.password())); // bcrypt 加盐哈希，明文不落库
        u.setCompany(req.company() == null || req.company().isBlank() ? region : req.company());
        u.setUserType(req.userType());
        u.setRegion(region);
        u.setRegionCode(regionCode);
        u.setStatus(req.status() == null ? "ENABLED" : req.status());
        u.setAuditStatus("APPROVED"); // 管理员新增账号默认审核通过，可直接登录
        userMapper.insert(u);

        assignRolesIfNeeded(u.getId(), req.roleIds());

        auditLogService.recordOperation("ADMIN-" + user.jobNo(), "user-account",
                "新增工作人员账号：" + req.jobNo());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", u.getId());
        data.put("job_no", u.getJobNo());
        return data;
    }

    /** 编辑账号（API 5.2 PUT）：仅更新传入的非空字段，密码单独重置 */
    @Transactional
    public Map<String, Object> update(Long id, UserUpdateRequest req, LoginUser user) {
        // 更新数据
        SysUser exist = requireUser(id);
        if (req.phone() != null && !req.phone().isBlank()
                && !req.phone().equals(exist.getPhone()) && userMapper.countByPhone(req.phone()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该手机号已被使用，请核对信息");
        }
        String userType = req.userType() != null ? req.userType() : exist.getUserType();
        String region = req.region() != null ? req.region() : exist.getRegion();
        String regionCode = req.regionCode() != null ? req.regionCode() : exist.getRegionCode();
        // 部门账号（8大分类）：选中区县时按「区县+分类」自动重算拼接名
        if (DEPT_USER_TYPES.contains(userType)
                && req.regionCode() != null && !req.regionCode().isBlank()) {
            SysRegion county = regionService.getByCode(req.regionCode());
            if (county == null || !"COUNTY".equals(county.getLevel())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "请选择正确的所属区县");
            }
            region = county.getRegionName() + AuthService.DEPT_NAME.getOrDefault(userType, userType);
            regionCode = county.getRegionCode();
        }
        SysUser u = new SysUser();
        u.setId(id);
        u.setName(req.name() != null ? req.name() : exist.getName());
        u.setCompany(req.company() != null ? req.company() : exist.getCompany());
        u.setUserType(userType);
        u.setRegion(region);
        u.setRegionCode(regionCode);
        u.setPhone(req.phone() != null ? req.phone() : exist.getPhone());
        userMapper.update(u);

        if (req.password() != null && !req.password().isBlank()) {
            // 管理员重置密码：DB 原子递增密码版本（事实源）并同步 Redis 缓存，踢出该账号全部旧 Token
            userMapper.updatePasswordWithVersion(id, BCrypt.hashpw(req.password()));
            Integer ver = userMapper.selectPwdVersion(id);
            redisService.set(RedisService.PWD_VERSION_PREFIX + id,
                    String.valueOf(ver == null ? 0 : ver), 30L * 24 * 3600);
        }

        auditLogService.recordOperation("ADMIN-" + user.jobNo(), "user-account",
                "编辑工作人员账号：" + exist.getJobNo());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        return data;
    }

    /** 删除账号（API 5.2 DELETE）：逻辑删除，保留历史操作记录 */
    @Transactional
    public Map<String, Object> delete(Long id, LoginUser user) {
        // 删除数据
        SysUser exist = requireUser(id);
        userMapper.logicalDelete(id);
        auditLogService.recordOperation("ADMIN-" + user.jobNo(), "user-account",
                "删除工作人员账号：" + exist.getJobNo());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        return data;
    }

    /** 账号状态切换（API 5.6）：启用/停用，与审核状态相互独立 */
    @Transactional
    public Map<String, Object> changeStatus(Long id, String status, LoginUser user) {
        // 修改Status
        SysUser exist = requireUser(id);
        userMapper.updateStatus(id, status);
        auditLogService.recordOperation("ADMIN-" + user.jobNo(), "user-account",
                ("DISABLED".equals(status) ? "停用" : "启用") + "工作人员账号：" + exist.getJobNo());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        return data;
    }

    /** 查询用户角色（GET 对应 API 5.9） */
    public Map<String, Object> getRoles(Long id) {
        requireUser(id);
        List<Long> roleIds = userRoleMapper.selectRoleIdsByUserId(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role_ids", roleIds);
        return data;
    }

    /** 用户角色分配（API 5.9）：先清空再写入，即时生效并留痕 */
    @Transactional
    public Map<String, Object> assignRoles(Long id, UserRoleAssignRequest req, LoginUser user) {
        // 分配Roles
        SysUser exist = requireUser(id);
        validateRoles(req.roleIds());
        userRoleMapper.deleteByUserId(id);
        if (!req.roleIds().isEmpty()) {
            userRoleMapper.insertBatch(id, req.roleIds());
        }
        auditLogService.recordOperation("ADMIN-" + user.jobNo(), "user-account",
                "为用户 " + exist.getJobNo() + " 分配角色：" + req.roleIds());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role_ids", req.roleIds());
        return data;
    }

    private SysUser requireUser(Long id) {
        // 执行requireUser操作
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "账号不存在");
        }
        return u;
    }

    private void assignRolesIfNeeded(Long userId, List<Long> roleIds) {
        // 分配RolesIfNeeded
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        validateRoles(roleIds);
        userRoleMapper.insertBatch(userId, roleIds);
    }

    private void validateRoles(List<Long> roleIds) {
        // 校验Roles
        if (roleIds == null) {
            return;
        }
        List<Long> all = roleMapper.selectAll().stream().map(SysRole::getId).toList();
        for (Long roleId : roleIds) {
            if (!all.contains(roleId)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "角色ID不存在：" + roleId);
            }
        }
    }

    private Map<String, Object> toVo(Map<String, Object> r) {
        // 执行toVo操作
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("job_no", r.get("job_no"));
        m.put("name", r.get("name"));
        m.put("company", r.get("company"));
        m.put("user_type", r.get("user_type"));
        m.put("region", r.get("region"));
        m.put("region_code", r.get("region_code"));
        m.put("phone", MaskUtil.maskPhone((String) r.get("phone")));
        m.put("status", r.get("status"));
        m.put("audit_status", r.get("audit_status"));
        m.put("create_time", r.get("create_time"));
        return m;
    }
}