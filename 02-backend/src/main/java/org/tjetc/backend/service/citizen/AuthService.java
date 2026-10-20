package org.tjetc.backend.service.citizen;

import cn.hutool.crypto.digest.BCrypt;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.common.*;
import org.tjetc.backend.dto.admin.ChangePasswordRequest;
import org.tjetc.backend.dto.citizen.CitizenRegisterRequest;
import org.tjetc.backend.dto.citizen.CitizenEmailLoginRequest;
import org.tjetc.backend.dto.citizen.CitizenLoginRequest;
import org.tjetc.backend.dto.citizen.JobNoLoginRequest;
import org.tjetc.backend.dto.citizen.ResetPasswordRequest;
import org.tjetc.backend.dto.citizen.StaffDeptRegisterRequest;
import org.tjetc.backend.dto.citizen.StaffRegisterRequest;
import org.tjetc.backend.dto.citizen.StreetRegisterRequest;
import org.tjetc.backend.entity.admin.SysAccountAudit;
import org.tjetc.backend.entity.admin.SysRole;
import org.tjetc.backend.entity.common.SysRegion;
import org.tjetc.backend.entity.common.SysUser;
import org.tjetc.backend.entity.citizen.CitizenAccount;
import org.tjetc.backend.mapper.admin.SysAccountAuditMapper;
import org.tjetc.backend.mapper.admin.SysRoleMapper;
import org.tjetc.backend.mapper.common.SysUserMapper;
import org.tjetc.backend.mapper.citizen.CitizenAccountMapper;
import org.tjetc.backend.service.admin.SysLoginLogService;
import org.tjetc.backend.service.common.RedisService;
import org.tjetc.backend.service.common.RegionService;
import org.tjetc.backend.util.JwtUtil;
import org.tjetc.backend.util.MaskUtil;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认证服务（需求 2 用户注册 / 3 用户登录 / 18 个人基础功能 / API 3.1/3.3/5.1/9.1/9.10）
 * - 密码 bcrypt 加盐哈希存储（明文不落库）
 * - 连续 5 次密码错误锁定 10 分钟（Redis 计数）
 * - 登录结果记录 sys_login_log
 * - 退出登录 Token 加入 Redis 黑名单
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Set<String> STAFF_TYPES = Set.of("STREET", "URBAN", "PROPERTY", "LIVELIHOOD");

    /** 部门端完整 user_type：8 大分派部门 + 街道办（用于忘记密码的归属校验） */
    private static final Set<String> DEPT_TYPES = Set.of(
            "URBAN", "TRAFFIC", "MARKET", "ENVIRONMENT", "HOUSING", "LABOR", "PUBLIC", "HEALTH", "STREET");

    /** 部门注册允许的 user_type：仅 8 大分派部门（不含 STREET，街道办走独立注册入口） */
    private static final Set<String> DEPT_REGISTER_TYPES = Set.of(
            "URBAN", "TRAFFIC", "MARKET", "ENVIRONMENT", "HOUSING", "LABOR", "PUBLIC", "HEALTH");

    /** user_type → 部门中文名（注册/建号时写入 company/region 展示用；供管理端建号复用） */
    public static final java.util.Map<String, String> DEPT_NAME = java.util.Map.of(
            "URBAN", "城管部门", "TRAFFIC", "交通部门", "MARKET", "市场监管部门",
            "ENVIRONMENT", "生态环境部门", "HOUSING", "住建部门", "LABOR", "人社部门",
            "PUBLIC", "市政部门", "HEALTH", "卫健部门", "STREET", "街道办");

    @Value("${app.jwt.expire-seconds:7200}")
    private long expireSeconds;

    @Value("${app.login.max-fail-count:5}")
    private int maxFailCount;

    @Value("${app.login.lock-seconds:600}")
    private long lockSeconds;

    private final SysUserMapper userMapper;
    private final CitizenAccountMapper citizenAccountMapper;
    private final SysAccountAuditMapper auditMapper;
    private final SysRoleMapper roleMapper;
    private final SysLoginLogService loginLogService;
    private final SmsService smsService;
    private final EmailCodeService emailCodeService;
    private final RedisService redisService;
    private final RegionService regionService;
    private final JwtUtil jwtUtil;

    // ------------------------------------------------------------------ 注册

    /**
     * 工作人员自助注册（API 3.1）：
     * QQ 邮箱验证码校验（替换原短信验证码）、工号/手机号/邮箱唯一性校验、
     * 确认密码校验、bcrypt 加密、审核状态默认 PENDING（不可登录）
     */
    @Transactional
    public Map<String, Object> register(StaffRegisterRequest req) {
        // 用户注册
        emailCodeService.verify(req.email(), "REGISTER", req.emailCode());
        if (!req.password().equals(req.confirmPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "两次输入的密码不匹配");
        }
        if (userMapper.countByJobNo(req.jobNo()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该工号已注册，请核对信息");
        }
        if (userMapper.countByPhone(req.phone()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该手机号已被注册，请核对信息");
        }
        if (userMapper.countByEmail(req.email()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该邮箱已被注册，请核对信息");
        }
        SysUser user = new SysUser();
        user.setJobNo(req.jobNo());
        user.setName(req.name());
        user.setPhone(req.phone());
        user.setEmail(req.email());
        user.setPassword(BCrypt.hashpw(req.password())); // bcrypt 加盐哈希，明文不落库
        user.setCompany(req.company());
        user.setUserType(req.userType());
        user.setRegion(req.region());
        user.setStatus("ENABLED");
        user.setAuditStatus("PENDING"); // 提交后进入待审核状态，无法登录
        userMapper.insert(user);

        // 生成待审核记录（sys_account_audit）
        SysAccountAudit audit = new SysAccountAudit();
        audit.setUserId(user.getId());
        audit.setAuditStatus("PENDING");
        audit.setApplyTime(LocalDateTime.now());
        auditMapper.insert(audit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", user.getId());
        data.put("audit_status", "PENDING");
        return data;
    }

    /**
     * 市民自助注册：短信验证码校验 + 落 citizen_account 表（姓名/邮箱/辖区/街道/住址，bcrypt 密码）。
     */
    public Map<String, Object> citizenRegister(CitizenRegisterRequest req) {
        smsService.verify(req.phone(), "REGISTER", req.smsCode());
        if (!req.password().equals(req.confirmPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "两次输入的密码不匹配");
        }
        if (citizenAccountMapper.countByPhone(req.phone()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该手机号已注册，请直接登录");
        }
        if (req.email() != null && !req.email().isBlank() && citizenAccountMapper.countByEmail(req.email()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该邮箱已被注册，请核对信息");
        }
        CitizenAccount account = new CitizenAccount();
        account.setPhone(req.phone());
        account.setName(req.name());
        account.setEmail(req.email());
        account.setPassword(BCrypt.hashpw(req.password()));
        account.setDistrictCode(req.districtCode());
        account.setDistrict(req.district());
        account.setStreetCode(req.streetCode());
        account.setStreet(req.street());
        account.setAddressDetail(req.addressDetail());
        citizenAccountMapper.insert(account);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phone", MaskUtil.maskPhone(req.phone()));
        data.put("name", req.name());
        data.put("message", "注册成功，请使用短信验证码或邮箱验证码登录");
        return data;
    }

    /**
     * 市民邮箱验证码登录：邮箱必须已注册（citizen_account 有记录），验证码经邮箱发送后校验，返回市民 JWT。
     */
    public Map<String, Object> citizenEmailLogin(CitizenEmailLoginRequest req, String ip, String device) {
        // 仅已注册市民可登录（未注册邮箱提示先注册）
        CitizenAccount account = citizenAccountMapper.selectByEmail(req.email());
        if (account == null) {
            loginLogService.record(null, "EMAIL_CODE", ip, device, "FAIL");
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "未注册，请先注册后登录");
        }
        emailCodeService.verify(req.email(), "LOGIN", req.emailCode());
        loginLogService.record(null, "EMAIL_CODE", ip, device, "SUCCESS");
        String token = jwtUtil.createToken(null, "CITIZEN", null, null, account.getPhone());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("access_token", token);
        data.put("expires_in", expireSeconds);
        data.put("user_type", "CITIZEN");
        data.put("masked_phone", MaskUtil.maskPhone(account.getPhone()));
        data.put("email", account.getEmail());
        return data;
    }

    /** 市民邮箱登录验证码发送前置校验：仅向已注册邮箱发送（未注册提示先注册，防止误发/探测） */
    public void requireCitizenEmailRegistered(String email) {
        if (citizenAccountMapper.selectByEmail(email) == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "未注册，请先注册后登录");
        }
    }

    /** 市民手机号登录验证码发送前置校验：仅向已注册手机号发送（未注册提示先注册，与邮箱口径一致） */
    public void requireCitizenPhoneRegistered(String phone) {
        if (citizenAccountMapper.selectByPhone(phone) == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "未注册，请先注册后登录");
        }
    }

    /**
     * 市民个人资料（个人中心展示）：按 JWT 手机号查询 citizen_account。
     */
    public Map<String, Object> citizenProfile(String phone) {
        CitizenAccount account = citizenAccountMapper.selectByPhone(phone);
        Map<String, Object> data = new LinkedHashMap<>();
        if (account == null) {
            data.put("registered", false);
            data.put("masked_phone", MaskUtil.maskPhone(phone));
            return data;
        }
        data.put("registered", true);
        data.put("name", account.getName());
        data.put("phone", account.getPhone());
        data.put("masked_phone", MaskUtil.maskPhone(account.getPhone()));
        data.put("email", account.getEmail());
        data.put("district_code", account.getDistrictCode());
        data.put("district", account.getDistrict());
        data.put("street_code", account.getStreetCode());
        data.put("street", account.getStreet());
        data.put("address_detail", account.getAddressDetail());
        data.put("create_time", account.getCreateTime());
        return data;
    }

    // -------------------------------------------------- 部门注册/忘记密码

    /**
     * 部门工作人员自助注册：姓名 + 工号 + 手机号 + 部门类型 + 短信验证码；
     * 工号由用户手动填写（入职已有工号，不为空且唯一），注册后进入待审核状态（PENDING）
     */
    @Transactional
    public Map<String, Object> staffDeptRegister(StaffDeptRegisterRequest req) {
        // 短信验证码校验（scene=STAFF_REGISTER）
        smsService.verify(req.phone(), "STAFF_REGISTER", req.smsCode());
        if (!DEPT_REGISTER_TYPES.contains(req.userType())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "所属部门不正确，请重新选择");
        }
        if (!req.password().equals(req.confirmPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "两次输入的密码不匹配");
        }
        if (userMapper.countByJobNo(req.jobNo()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该工号已被注册，请核对信息");
        }
        if (userMapper.countByPhone(req.phone()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该手机号已被注册，请复核");
        }
        // 区县合法性：存在且层级为 COUNTY（部门账号按区县划分，56 = 7区县 × 8分类）
        SysRegion county = regionService.getByCode(req.countyCode());
        if (county == null || !"COUNTY".equals(county.getLevel())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择正确的所属区县");
        }
        String deptName = DEPT_NAME.getOrDefault(req.userType(), req.userType());
        SysUser user = new SysUser();
        user.setJobNo(req.jobNo());                  // 工号由用户手动填写，不再自动分配
        user.setName(req.name());
        user.setPhone(req.phone());
        user.setEmail("");                       // 部门注册不采集邮箱，置空占位
        user.setPassword(BCrypt.hashpw(req.password())); // bcrypt 加盐哈希
        user.setCompany(county.getRegionName() + deptName);   // 单位 = 区县+部门拼接名
        user.setUserType(req.userType());
        user.setRegion(county.getRegionName() + deptName);    // 辖区 = 区县+部门拼接名
        user.setRegionCode(req.countyCode());            // 区划编码 = 区县级 sys_region.region_code
        user.setStatus("DISABLED");              // 注册后未启用，待管理员审核通过后才可登录
        user.setAuditStatus("PENDING");          // 注册后待管理员审核
        userMapper.insert(user);

        // 生成待审核记录（sys_account_audit）
        SysAccountAudit audit = new SysAccountAudit();
        audit.setUserId(user.getId());
        audit.setAuditStatus("PENDING");
        audit.setApplyTime(LocalDateTime.now());
        auditMapper.insert(audit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("job_no", req.jobNo());
        data.put("user_id", user.getId());
        data.put("audit_status", "PENDING");
        return data;
    }

    /**
     * 街道办工作人员自助注册：姓名 + 工号 + 区县 + 街道/镇/乡 + 手机号 + 短信验证码；
     * 工号由用户手动填写，地区按「区县 → 街道/镇/乡」两级级联，区划编码来自 sys_region。
     */
    @Transactional
    public Map<String, Object> streetRegister(StreetRegisterRequest req) {
        if (!req.password().equals(req.confirmPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "两次输入的密码不匹配");
        }
        if (userMapper.countByJobNo(req.jobNo()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该工号已被注册，请核对信息");
        }
        if (userMapper.countByPhone(req.phone()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该手机号已被注册，请复核");
        }
        // 区县合法性：存在且层级为 COUNTY
        SysRegion county = regionService.getByCode(req.countyCode());
        if (county == null || !"COUNTY".equals(county.getLevel())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择正确的所属区县");
        }
        // 街道/镇/乡合法性：存在、层级属于乡镇级、且 parent_code 命中所选区县
        SysRegion street = regionService.getByCode(req.streetCode());
        if (street == null
                || !Set.of("STREET", "TOWN", "TOWNSHIP").contains(street.getLevel())
                || !req.countyCode().equals(street.getParentCode())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择正确的所属街道/镇/乡");
        }
        // 短信验证码校验（scene=STREET_REGISTER，置于地区校验之后避免误耗验证码）
        smsService.verify(req.phone(), "STREET_REGISTER", req.smsCode());

        SysUser user = new SysUser();
        user.setJobNo(req.jobNo());                  // 工号由用户手动填写
        user.setName(req.name());
        user.setPhone(req.phone());
        user.setEmail("");                       // 街道办注册不采集邮箱，置空占位
        user.setPassword(BCrypt.hashpw(req.password())); // bcrypt 加盐哈希
        user.setCompany("街道办");                  // 单位统一为"街道办"
        user.setUserType("STREET");
        user.setRegion(street.getRegionName());      // 辖区 = 街道/镇/乡中文名
        user.setStatus("DISABLED");              // 注册后未启用，待管理员审核通过后才可登录
        user.setAuditStatus("PENDING");          // 注册后待管理员审核
        userMapper.insert(user);

        // 生成待审核记录（sys_account_audit）
        SysAccountAudit audit = new SysAccountAudit();
        audit.setUserId(user.getId());
        audit.setAuditStatus("PENDING");
        audit.setApplyTime(LocalDateTime.now());
        auditMapper.insert(audit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("job_no", req.jobNo());
        data.put("user_id", user.getId());
        data.put("audit_status", "PENDING");
        return data;
    }

    /**
     * 忘记密码：手机号 + 短信验证码 重置登录密码（不改变工号/手机号）；
     * 重置成功后密码版本递增，使该手机号对应的旧 Token 立即失效
     */
    @Transactional
    public Map<String, Object> resetPassword(ResetPasswordRequest req) {
        // 短信验证码校验
        smsService.verify(req.phone(), "RESET_PASSWORD", req.smsCode());
        if (!req.password().equals(req.confirmPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "两次输入的新密码不匹配");
        }
        SysUser user = userMapper.selectByLoginAccount(req.phone());
        if (user == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该手机号未注册，请核对或注册后再试");
        }
        if (!DEPT_TYPES.contains(user.getUserType())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该手机号不属于部门工作人员，无法重置密码");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(BCrypt.hashpw(req.password()));
        // 重置密码：DB 原子递增密码版本（事实源），Redis 缓存对齐，踢出旧 Token
        userMapper.updatePasswordWithVersion(user.getId(), update.getPassword());
        syncPwdVersionCache(user.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updated", true);
        return data;
    }

    // --------------------------------------------------------------- 市民登录

    /**
     * 市民验证码登录（API 3.3）：手机号 + 短信验证码，返回短期 JWT（默认2小时）。
     * 登录前置校验：手机号必须已注册（citizen_account 有记录），未注册提示先注册
     * （校验放在验证码 verify 之前，未注册时不消耗一次性验证码）
     */
    public Map<String, Object> citizenLogin(CitizenLoginRequest req, String ip, String device) {
        // 仅已注册市民可登录（未注册手机号提示先注册，与邮箱登录口径一致）
        if (citizenAccountMapper.selectByPhone(req.phone()) == null) {
            loginLogService.record(null, "SMS_CODE", ip, device, "FAIL");
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "未注册，请先注册后登录");
        }
        smsService.verify(req.phone(), "LOGIN", req.smsCode());
        loginLogService.record(null, "SMS_CODE", ip, device, "SUCCESS");
        String token = jwtUtil.createToken(null, "CITIZEN", null, null, req.phone());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("access_token", token);
        data.put("expires_in", expireSeconds);
        data.put("user_type", "CITIZEN");
        data.put("masked_phone", MaskUtil.maskPhone(req.phone()));
        return data;
    }

    // --------------------------------------------------------- 工号密码登录

    /**
     * 工号 + 密码登录（API 5.1 管理员 / API 9.1 部门人员）：
     * 校验顺序（需求 3(6)-(9)）：锁定 → 账号存在性（统一提示）→ 审核状态 → 账号状态 → 密码
     */
    public Map<String, Object> jobNoLogin(JobNoLoginRequest req, Set<String> allowedTypes,
                                          String ip, String device) {
        // 登录失败锁定：连续5次密码错误锁定10分钟
        if (redisService.hasKey(RedisService.LOGIN_LOCK_PREFIX + req.jobNo())) {
            throw new BusinessException(ResultCode.ACCOUNT_LOCKED,
                    "登录失败次数超限，账号已锁定，请" + redisService.ttlSeconds(RedisService.LOGIN_LOCK_PREFIX + req.jobNo()) / 60 + "分钟后再试");
        }
        // 支持工号或手机号任一作为登录账号（要求 部门手机号/工号共用登录）
        SysUser user = userMapper.selectByLoginAccount(req.jobNo());
        // 账号不存在：统一提示"账号或密码错误"，不单独提示账号不存在（避免账号信息泄露）
        if (user == null) {
            recordFail(req.jobNo(), null, ip, device);
            throw new BusinessException(ResultCode.PARAM_ERROR, "账号或密码错误");
        }
        // 审核状态校验：待审核/已驳回禁止登录
        if ("PENDING".equals(user.getAuditStatus())) {
            throw new BusinessException(ResultCode.ACCOUNT_NOT_APPROVED, "账号未通过审核，请联系系统管理员");
        }
        if ("REJECTED".equals(user.getAuditStatus())) {
            String reason = selectLatestRejectReason(user.getId());
            throw new BusinessException(ResultCode.ACCOUNT_NOT_APPROVED,
                    reason == null ? "账号未通过审核，请联系系统管理员"
                            : "账号未通过审核，请联系系统管理员（驳回原因：" + reason + "）");
        }
        // 账号状态校验：已停用禁止登录
        if ("DISABLED".equals(user.getStatus())) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED, "账号已停用，请联系系统管理员");
        }
        // 登录入口与用户类型匹配（权限隔离）
        if (!allowedTypes.contains(user.getUserType())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        // bcrypt 比对，失败统一提示"账号或密码错误"
        if (!BCrypt.checkpw(req.password(), user.getPassword())) {
            recordFail(req.jobNo(), user.getId(), ip, device);
            throw new BusinessException(ResultCode.PARAM_ERROR, "账号或密码错误");
        }
        // 登录成功：清除失败计数
        redisService.delete(RedisService.LOGIN_FAIL_PREFIX + req.jobNo());
        loginLogService.record(user.getId(), "JOB_NO_PASSWORD", ip, device, "SUCCESS");

        // 读取当前密码版本写入 Token（改密后版本递增，旧 Token 立即失效）
        // 部门账号携带区县级 region_code，部门端按「分类+区县」过滤
        String token = jwtUtil.createToken(user.getId(), user.getUserType(), user.getRegion(),
                user.getRegionCode(), user.getJobNo(), user.getPhone(), currentPwdVersion(user.getId()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("access_token", token);
        data.put("expires_in", expireSeconds);
        data.put("user_info", buildUserInfo(user));
        return data;
    }

    /** 管理员登录 user_info 含角色与权限（API 5.1）；部门人员含 user_type/region（API 9.1） */
    private Map<String, Object> buildUserInfo(SysUser user) {
        // 构建UserInfo
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", user.getId());
        info.put("name", user.getName());
        info.put("job_no", user.getJobNo());
        if ("ADMIN".equals(user.getUserType())) {
            List<String> roles = roleMapper.selectByUserId(user.getId()).stream().map(SysRole::getCode).toList();
            info.put("roles", roles);
            info.put("perms", List.of("*"));
        } else {
            info.put("user_type", user.getUserType());
            info.put("region", user.getRegion());
            info.put("region_code", user.getRegionCode());
        }
        return info;
    }

    /** 登录失败：计数，连续5次锁定10分钟 */
    private void recordFail(String jobNo, Long userId, String ip, String device) {
        // 执行recordFail操作
        loginLogService.record(userId, "JOB_NO_PASSWORD", ip, device, "FAIL");
        long failCount = redisService.increment(RedisService.LOGIN_FAIL_PREFIX + jobNo, lockSeconds);
        if (failCount >= maxFailCount) {
            redisService.set(RedisService.LOGIN_LOCK_PREFIX + jobNo, "1", lockSeconds);
            redisService.delete(RedisService.LOGIN_FAIL_PREFIX + jobNo);
        }
    }

    private String selectLatestRejectReason(Long userId) {
        // 查询LatestRejectReason
        return auditMapper.selectLatestRejectReason(userId);
    }

    // ------------------------------------------------------------------ 注销

    /**
     * 退出登录：Token 加入 Redis 黑名单，过期时间对齐剩余有效期（API 2.2 Token 注销）
     */
    public void logout(String token) {
        // 用户登出
        long remaining = jwtUtil.remainingSeconds(token);
        if (remaining > 0) {
            redisService.set(RedisService.TOKEN_BLACKLIST_PREFIX + token, "1", remaining);
        }
    }

    // --------------------------------------------------------------- 修改密码

    /**
     * 修改登录密码（API 9.10）：原密码校验、新密码强度、两次一致、bcrypt 加密更新
     */
    public void changePassword(Long userId, ChangePasswordRequest req) {
        // 修改密码
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND);
        }
        if (!BCrypt.checkpw(req.oldPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "原密码错误");
        }
        if (!req.newPassword().equals(req.confirmPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "两次输入的新密码不匹配");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(BCrypt.hashpw(req.newPassword()));
        // 改密：DB 原子递增密码版本（事实源），Redis 缓存对齐，踢出该用户全部旧 Token（防盗号后残留会话）
        userMapper.updatePasswordWithVersion(userId, update.getPassword());
        syncPwdVersionCache(userId);
    }

    /** 改密/重置密码后同步：读取 DB 密码版本写回 Redis 缓存（DB 为事实源，Redis 仅做加速） */
    private void syncPwdVersionCache(Long userId) {
        Integer ver = userMapper.selectPwdVersion(userId);
        redisService.set(RedisService.PWD_VERSION_PREFIX + userId,
                String.valueOf(ver == null ? 0 : ver), 30L * 24 * 3600);
    }

    /** 读取用户当前密码版本：Redis 优先；缓存缺失回退 DB 并回写缓存（Redis 丢数据不再误判版本为 0） */
    private long currentPwdVersion(Long userId) {
        String ver = redisService.get(RedisService.PWD_VERSION_PREFIX + userId);
        if (ver != null) {
            return Long.parseLong(ver);
        }
        Integer dbVer = userMapper.selectPwdVersion(userId);
        long v = dbVer == null ? 0L : dbVer;
        redisService.set(RedisService.PWD_VERSION_PREFIX + userId, String.valueOf(v), 30L * 24 * 3600);
        return v;
    }

    public static boolean isStaffType(String userType) {
        return STAFF_TYPES.contains(userType);
    }
}