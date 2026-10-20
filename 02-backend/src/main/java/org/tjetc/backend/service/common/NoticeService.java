package org.tjetc.backend.service.common;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.NoticeUpsertRequest;
import org.tjetc.backend.dto.NoticeVO;
import org.tjetc.backend.entity.common.CitizenNotice;
import org.tjetc.backend.entity.common.DeptNotice;
import org.tjetc.backend.entity.common.Notice;
import org.tjetc.backend.entity.common.NoticeTarget;
import org.tjetc.backend.entity.common.StreetNotice;
import org.tjetc.backend.entity.common.SysRegion;
import org.tjetc.backend.mapper.admin.AdminNoticeMapper;
import org.tjetc.backend.mapper.admin.ClassifyPrimaryMapper;
import org.tjetc.backend.mapper.citizen.CitizenNoticeMapper;
import org.tjetc.backend.mapper.dept.DeptNoticeMapper;
import org.tjetc.backend.mapper.street.StreetNoticeMapper;
import org.tjetc.backend.entity.admin.ClassifyPrimary;
import org.tjetc.backend.service.admin.AuditLogService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通知公告服务（四端拆分版）——通知公告按四端解耦：
 *   admin_notice   管理端公告主表（全量权威，所有端发布的公告均落本表）
 *   dept_notice    部门端收件箱（管理端/部门端发布后按 DEPT 目标扇出的部门级快照，含部门级已读）
 *   street_notice  街道办端收件箱（管理端/部门端发布后按 STREET 目标扇出，含街道办级已读）
 *   citizen_notice 市民端公开栏（PUBLIC 目标扇出，市民端只接收不发布，无已读跟踪）
 *
 * 发布权限矩阵（高权力→低权力，上级只能发给下级）：
 *   ADMIN  → 部门(DEPT) + 街道办(STREET) + 市民公开(PUBLIC)
 *   DEPT   → 街道办(STREET) + 市民公开(PUBLIC)（不能发给部门）
 *   STREET → 市民公开(PUBLIC)（只能发给市民）
 *
 * 联动语义：
 *   · 发布/重发：写主表 → 按目标扇出三收件箱副本（重发=删旧副本重建，已读清零重新提醒）
 *   · 编辑：内容/标题/摘要传播到已存在的收件箱副本（保留已读）；接收对象差异同步（新增目标插副本、移除目标删副本）
 *   · 下架：主表 + 三收件箱同步 status=0（接收方列表/红点即时消失）
 *   · 已读：部门/街道办收件箱行 is_read（部门级/街道办级粒度，点开详情置 1）
 *   · 审计：发布/编辑/下架/重发/删除均写三端审计日志（recordOperation 按操作人自动路由）
 * 原 notice_target（接收对象）/ notice_read（逐条已读）两表已随拆分移除。
 */
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final AdminNoticeMapper adminNoticeMapper;
    private final DeptNoticeMapper deptNoticeMapper;
    private final StreetNoticeMapper streetNoticeMapper;
    private final CitizenNoticeMapper citizenNoticeMapper;
    private final ClassifyPrimaryMapper classifyPrimaryMapper;
    private final RegionService regionService;
    private final AuditLogService auditLogService;

    // ============ 公众端（市民端公开栏：citizen_notice，只接收） ============

    /** 公众端：分页（仅 status=1 展示中的公开通知；keyword 标题/正文/摘要模糊匹配） */
    public PageData<Notice> publicPage(String keyword, int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Notice> list = citizenNoticeMapper.selectPublicPage(keyword, offset, pageSize);
        long total = citizenNoticeMapper.countPublic(keyword);
        return PageData.of(list, total, page, pageSize);
    }

    /** 公众端：首页顶部最多 N 条（默认 3） */
    public List<Notice> publicTop(int limit) {
        return citizenNoticeMapper.selectPublicTop(Math.max(1, limit));
    }

    /** 公众端：详情（必须 status=1 展示中的公开通知） */
    public Notice publicDetail(Long id) {
        CitizenNotice n = citizenNoticeMapper.selectByNoticeId(id);
        if (n == null || n.getStatus() == null || n.getStatus() != 1) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "公告不存在或已下架");
        }
        return toNotice(n);
    }

    // ============ 管理端（admin_notice 主表 + 目标重构） ============

    /** 管理端：分页（标题/关键词模糊 + 状态/发布者/日期区间筛选，带接收对象摘要） */
    public PageData<NoticeVO> adminPage(String title, String keyword, Integer status,
                                        String publisherType, String publisherDept,
                                        String startDate, String endDate,
                                        int page, int pageSize) {
        LocalDateTime[] range = dateRange(startDate, endDate);
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Notice> list = adminNoticeMapper.selectPage(title, keyword, status, publisherType, publisherDept,
                range[0], range[1], offset, pageSize);
        long total = adminNoticeMapper.countPage(title, keyword, status, publisherType, publisherDept, range[0], range[1]);
        List<NoticeVO> vos = decorateWithTargets(list);
        return PageData.of(vos, total, page, pageSize);
    }

    /** 管理端：发布（发布者可选管理员本人或八大部门名义，接收对象部门/街道办/市民公开灵活多选） */
    @Transactional
    public NoticeVO adminCreate(NoticeUpsertRequest request) {
        Publisher publisher = resolveAdminPublisher(request.publisherType(), request.publisherDept());
        List<NoticeTarget> targets = buildTargets(request.targets(), PublishLevel.ADMIN);
        Notice notice = insertNotice(request, publisher);
        fanOut(notice, targets);
        audit("发布通知：《" + notice.getTitle() + "》（" + targetSummaryOf(targets) + "）");
        return adminDetail(notice.getId());
    }

    /** 管理端：编辑（标题/正文/接收对象可改，名义发布者不可改；已读保留，目标差异同步） */
    @Transactional
    public NoticeVO adminUpdate(Long id, NoticeUpsertRequest request) {
        Notice exist = requireNotice(id);
        List<NoticeTarget> newTargets = buildTargets(request.targets(), PublishLevel.ADMIN);
        applyEdit(id, request, exist, newTargets, false);
        audit("编辑通知：《" + exist.getTitle() + "》");
        return adminDetail(id);
    }

    /** 管理端：下架（status=0，不物理删除，接收方列表与红点即时消失） */
    @Transactional
    public void adminOffShelf(Long id) {
        Notice exist = requireNotice(id);
        offShelf(exist);
        audit("下架通知：《" + exist.getTitle() + "》");
    }

    /** 管理端：重新发布已下架通知（可改内容/接收对象；status=1、发布时间刷新、重建收件箱已读清零全员重新提醒） */
    @Transactional
    public NoticeVO adminRepublish(Long id, NoticeUpsertRequest request) {
        Notice exist = requireNotice(id);
        List<NoticeTarget> targets = buildTargets(request.targets(), PublishLevel.ADMIN);
        republish(exist, request, targets);
        audit("重新发布通知：《" + exist.getTitle() + "》");
        return adminDetail(id);
    }

    /** 管理端：详情（含接收对象明细，编辑回填用） */
    public NoticeVO adminDetail(Long id) {
        Notice exist = requireNotice(id);
        return decorateWithTargets(List.of(exist)).get(0);
    }

    /**
     * 管理端：发布弹窗下拉数据（发布者=管理员/56部门 + 接收对象=部门/全部街道办）
     * 部门目标按 7区县 × 8分类 共 56 项（56部门口径）：depts 平铺（code=区县code:分类code），dept_groups 按区县分组供两级选择
     * streets 按区县分组（发布弹窗两级选择：先选区县再勾街道办）
     * 返回 { depts: [{code,name}], dept_groups: [{county, depts:[{code,name}]}], streets: [{county, streets:[街道办名]}] }
     */
    public Map<String, Object> adminOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        List<Map<String, Object>> flat = new ArrayList<>();
        List<Map<String, Object>> groupList = new ArrayList<>();
        for (SysRegion county : regionService.listCounties()) {
            List<Map<String, Object>> group = new ArrayList<>();
            for (ClassifyPrimary p : classifyPrimaryMapper.selectEnabled()) {
                String code = county.getRegionCode() + ":" + p.getCode();
                String name = county.getRegionName() + p.getName();
                flat.add(Map.<String, Object>of("code", code, "name", name, "county", county.getRegionName()));
                group.add(Map.<String, Object>of("code", code, "name", name));
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("county", county.getRegionName());
            g.put("depts", group);
            groupList.add(g);
        }
        options.put("depts", flat);
        options.put("dept_groups", groupList);
        options.put("streets", groupedStreetOptions());
        return options;
    }

    /** 部门账号复合键（区县code:分类code；账号无区县时回退纯分类code，兼容过渡期） */
    private String deptKeyOf(LoginUser user) {
        return (user.regionCode() == null || user.regionCode().isBlank())
                ? user.userType() : user.regionCode() + ":" + user.userType();
    }

    /** 部门标识 → 展示名：区县code:分类code 复合键 → 区县名+分类名；纯分类code → 分类名（兼容历史） */
    private String deptDisplayName(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        int idx = code.indexOf(':');
        String prefix = idx > 0 ? code.substring(0, idx) : null;
        String deptCode = idx > 0 ? code.substring(idx + 1) : code;
        String name = deptName(deptCode);
        if (prefix != null) {
            SysRegion county = regionService.getByCode(prefix);
            String countyName = county == null ? prefix : county.getRegionName();
            return countyName + name;
        }
        return name;
    }

    /** 部门目标校验（56部门口径）：纯分类code=全市该分类；区县code:分类code=仅该区县 */
    private void validateDeptTarget(String code) {
        int idx = code.indexOf(':');
        String countyCodePart = idx > 0 ? code.substring(0, idx) : null;
        String deptCode = idx > 0 ? code.substring(idx + 1) : code;
        if (classifyPrimaryMapper.selectByCode(deptCode) == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "接收部门不存在：" + code);
        }
        if (countyCodePart != null) {
            SysRegion county = regionService.getByCode(countyCodePart);
            if (county == null || !"COUNTY".equals(county.getLevel())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "接收部门区县不存在：" + code);
            }
        }
    }

    /** 部门目标展开（扇出粒度）：纯分类code=全市 → 7 区县各一行（各区县已读独立）；复合键 → 单行 */
    private List<String> expandDeptTarget(String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        if (code.contains(":")) {
            return List.of(code);
        }
        List<String> codes = new ArrayList<>();
        for (SysRegion county : regionService.listCounties()) {
            codes.add(county.getRegionCode() + ":" + code);
        }
        return codes;
    }

    /** 部门目标总数（56=7区县×8分类，供「全部部门」折叠判断） */
    private int deptTargetCount() {
        return regionService.listCounties().size() * classifyPrimaryMapper.selectEnabled().size();
    }

    // ============ 部门端（dept_notice 收件箱 + 本部门发布） ============

    /** 部门端：收到的通知（dept_notice 收件箱，含部门级已读标记；发布者/关键词/日期区间筛选；56口径按「分类+区县」匹配收件） */
    public PageData<NoticeVO> deptReceived(LoginUser user, String publisherType, String publisherDept,
                                           String keyword, String startDate, String endDate, int page, int pageSize) {
        LocalDateTime[] range = dateRange(startDate, endDate);
        int offset = Math.max(0, (page - 1) * pageSize);
        List<NoticeVO> list = deptNoticeMapper.selectReceived(user.userType(), user.regionCode(),
                publisherType, publisherDept, keyword, range[0], range[1], offset, pageSize);
        long total = deptNoticeMapper.countReceived(user.userType(), user.regionCode(),
                publisherType, publisherDept, keyword, range[0], range[1]);
        decoratePublisherName(list);
        return PageData.of(list, total, page, pageSize);
    }

    /** 部门端：我发布的（本部门名义全部通知，含管理员代发，按本账号「区县:分类」复合键匹配；关键词/日期区间筛选） */
    public PageData<NoticeVO> deptPublished(LoginUser user, String keyword, String startDate, String endDate, int page, int pageSize) {
        LocalDateTime[] range = dateRange(startDate, endDate);
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Notice> list = adminNoticeMapper.selectPublishedByPublisher("DEPT", deptKeyOf(user),
                keyword, range[0], range[1], offset, pageSize);
        long total = adminNoticeMapper.countPublishedByPublisher("DEPT", deptKeyOf(user), keyword, range[0], range[1]);
        return PageData.of(decorateWithTargets(list), total, page, pageSize);
    }

    /** 部门端：发布（层级限制：仅街道办 + 市民公开，名义固定本部门「区县:分类」复合键） */
    @Transactional
    public NoticeVO deptCreate(LoginUser user, NoticeUpsertRequest request) {
        Publisher publisher = new Publisher("DEPT", deptKeyOf(user));
        List<NoticeTarget> targets = buildTargets(request.targets(), PublishLevel.DEPT);
        Notice notice = insertNotice(request, publisher);
        fanOut(notice, targets);
        audit("发布通知：《" + notice.getTitle() + "》（" + targetSummaryOf(targets) + "）");
        return adminDetail(notice.getId());
    }

    /** 部门端：编辑（仅本部门名下；名义发布者不可改；已读保留，目标差异同步） */
    @Transactional
    public NoticeVO deptUpdate(LoginUser user, Long id, NoticeUpsertRequest request) {
        Notice exist = requireDeptOwn(user, id);
        List<NoticeTarget> newTargets = buildTargets(request.targets(), PublishLevel.DEPT);
        applyEdit(id, request, exist, newTargets, false);
        audit("编辑通知：《" + exist.getTitle() + "》");
        return adminDetail(id);
    }

    /** 部门端：下架（仅本部门名下） */
    @Transactional
    public void deptOffShelf(LoginUser user, Long id) {
        Notice exist = requireDeptOwn(user, id);
        offShelf(exist);
        audit("下架通知：《" + exist.getTitle() + "》");
    }

    /** 部门端：重新发布已下架通知（仅本部门名下；可改内容/接收对象；重建收件箱已读清零） */
    @Transactional
    public NoticeVO deptRepublish(LoginUser user, Long id, NoticeUpsertRequest request) {
        Notice exist = requireDeptOwn(user, id);
        List<NoticeTarget> targets = buildTargets(request.targets(), PublishLevel.DEPT);
        republish(exist, request, targets);
        audit("重新发布通知：《" + exist.getTitle() + "》");
        return adminDetail(id);
    }

    /** 部门端：详情（校验确实发给了我部门；点开自动置部门级已读，幂等，红点减一；56口径按「分类+区县」匹配收件） */
    @Transactional
    public NoticeVO deptDetail(LoginUser user, Long id) {
        NoticeVO inbox = deptNoticeMapper.selectByNoticeAndDept(id, user.userType(), user.regionCode());
        if (inbox == null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "该通知未发送给本部门");
        }
        deptNoticeMapper.markRead(id, user.userType(), user.regionCode());
        NoticeVO vo = decorateWithTargets(List.of(requireNotice(id))).get(0);
        vo.setIsRead(1);
        return vo;
    }

    // ============ 街道办端（street_notice 收件箱 + 本街道发布，仅市民公开） ============

    /** 街道办端：收到的通知（street_notice 收件箱，含已读标记；发布者/关键词/日期区间筛选） */
    public PageData<NoticeVO> streetReceived(LoginUser user, String publisherType, String publisherDept,
                                             String keyword, String startDate, String endDate, int page, int pageSize) {
        LocalDateTime[] range = dateRange(startDate, endDate);
        int offset = Math.max(0, (page - 1) * pageSize);
        List<NoticeVO> list = streetNoticeMapper.selectReceived(user.region(), publisherType, publisherDept,
                keyword, range[0], range[1], offset, pageSize);
        long total = streetNoticeMapper.countReceived(user.region(), publisherType, publisherDept,
                keyword, range[0], range[1]);
        decoratePublisherName(list);
        return PageData.of(list, total, page, pageSize);
    }

    /** 街道办端：我发布的（本街道名义，仅市民公开；关键词/日期区间筛选） */
    public PageData<NoticeVO> streetPublished(LoginUser user, String keyword, String startDate, String endDate, int page, int pageSize) {
        LocalDateTime[] range = dateRange(startDate, endDate);
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Notice> list = adminNoticeMapper.selectPublishedByPublisher("STREET", null, keyword,
                range[0], range[1], offset, pageSize);
        long total = adminNoticeMapper.countPublishedByPublisher("STREET", null, keyword, range[0], range[1]);
        return PageData.of(decorateWithTargets(list), total, page, pageSize);
    }

    /** 街道办端：发布（层级限制：仅市民公开 PUBLIC，名义固定本街道） */
    @Transactional
    public NoticeVO streetCreate(LoginUser user, NoticeUpsertRequest request) {
        Publisher publisher = new Publisher("STREET", null);
        List<NoticeTarget> targets = buildTargets(request.targets(), PublishLevel.STREET);
        Notice notice = insertNotice(request, publisher);
        fanOut(notice, targets);
        audit("发布通知：《" + notice.getTitle() + "》（" + targetSummaryOf(targets) + "）");
        return adminDetail(notice.getId());
    }

    /** 街道办端：编辑（仅本街道名下；已读保留，目标差异同步） */
    @Transactional
    public NoticeVO streetUpdate(LoginUser user, Long id, NoticeUpsertRequest request) {
        Notice exist = requireStreetOwn(user, id);
        List<NoticeTarget> newTargets = buildTargets(request.targets(), PublishLevel.STREET);
        applyEdit(id, request, exist, newTargets, false);
        audit("编辑通知：《" + exist.getTitle() + "》");
        return adminDetail(id);
    }

    /** 街道办端：下架（仅本街道名下） */
    @Transactional
    public void streetOffShelf(LoginUser user, Long id) {
        Notice exist = requireStreetOwn(user, id);
        offShelf(exist);
        audit("下架通知：《" + exist.getTitle() + "》");
    }

    /** 街道办端：重新发布已下架通知（仅本街道名下；重建收件箱已读清零） */
    @Transactional
    public NoticeVO streetRepublish(LoginUser user, Long id, NoticeUpsertRequest request) {
        Notice exist = requireStreetOwn(user, id);
        List<NoticeTarget> targets = buildTargets(request.targets(), PublishLevel.STREET);
        republish(exist, request, targets);
        audit("重新发布通知：《" + exist.getTitle() + "》");
        return adminDetail(id);
    }

    /** 街道办端：详情（校验确实发给了我街道办；点开自动置已读） */
    @Transactional
    public NoticeVO streetDetail(LoginUser user, Long id) {
        NoticeVO inbox = streetNoticeMapper.selectByNoticeAndStreet(id, user.region());
        if (inbox == null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "该通知未发送给本街道办");
        }
        streetNoticeMapper.markRead(id, user.region());
        NoticeVO vo = decorateWithTargets(List.of(requireNotice(id))).get(0);
        vo.setIsRead(1);
        return vo;
    }

    // ============ 私有辅助 ============

    /** 名义发布者（类型 + 部门code） */
    private record Publisher(String type, String dept) {
    }

    /** 发布层级（决定接收目标白名单）：ADMIN > DEPT > STREET（高权力→低权力） */
    private enum PublishLevel { ADMIN, DEPT, STREET }

    /** 管理端发布者解析：ADMIN（本人）或八大部门之一（DEPT + 部门code 必须存在） */
    private Publisher resolveAdminPublisher(String publisherType, String publisherDept) {
        if (publisherType == null || publisherType.isBlank() || "ADMIN".equals(publisherType)) {
            return new Publisher("ADMIN", null);
        }
        if (!"DEPT".equals(publisherType)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "发布者类型不合法");
        }
        if (publisherDept == null || publisherDept.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "以部门名义发布时必须选择部门");
        }
        if (classifyPrimaryMapper.selectByCode(publisherDept) == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "发布部门不存在：" + publisherDept);
        }
        return new Publisher("DEPT", publisherDept);
    }

    /**
     * 构造接收目标集合（去重），按发布层级白名单校验（高权力→低权力）：
     * ADMIN 允许 DEPT/STREET/PUBLIC；DEPT 允许 STREET/PUBLIC；STREET 仅 PUBLIC
     */
    private List<NoticeTarget> buildTargets(NoticeUpsertRequest.Targets targets, PublishLevel level) {
        List<NoticeTarget> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (targets == null) {
            // 街道办层级默认强制市民公开（前端可不传目标）
            if (level == PublishLevel.STREET) {
                return List.of(publicTarget(seen));
            }
            throw new BusinessException(ResultCode.PARAM_ERROR, "请至少选择一个接收对象");
        }
        if (targets.depts() != null && !targets.depts().isEmpty()) {
            if (level != PublishLevel.ADMIN) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "本层级不能向部门发送通知");
            }
            for (String code : targets.depts()) {
                if (code != null && !code.isBlank() && seen.add("DEPT:" + code)) {
                    // 56部门口径：纯分类code=全市该分类；区县code:分类code=仅该区县
                    validateDeptTarget(code);
                    NoticeTarget t = new NoticeTarget();
                    t.setTargetType("DEPT");
                    t.setTargetCode(code);
                    result.add(t);
                }
            }
        }
        if (targets.streets() != null && !targets.streets().isEmpty()) {
            if (level == PublishLevel.STREET) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "街道办只能向市民公开发送通知");
            }
            Set<String> allStreets = allStreetNames();
            for (String name : targets.streets()) {
                if (name != null && !name.isBlank() && seen.add("STREET:" + name)) {
                    if (!allStreets.contains(name)) {
                        throw new BusinessException(ResultCode.PARAM_ERROR, "接收街道办不存在：" + name);
                    }
                    NoticeTarget t = new NoticeTarget();
                    t.setTargetType("STREET");
                    t.setTargetCode(name);
                    result.add(t);
                }
            }
        }
        if (Boolean.TRUE.equals(targets.publicFlag()) && seen.add("PUBLIC:")) {
            result.add(publicTarget(seen));
        }
        if (result.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请至少选择一个接收对象");
        }
        return result;
    }

    private NoticeTarget publicTarget(Set<String> seen) {
        seen.add("PUBLIC:");
        NoticeTarget t = new NoticeTarget();
        t.setTargetType("PUBLIC");
        t.setTargetCode(null);
        return t;
    }

    /** 落库公告主体（摘要自动截正文前 100 字） */
    private Notice insertNotice(NoticeUpsertRequest request, Publisher publisher) {
        if (request.title() == null || request.title().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "标题不能为空");
        }
        Notice notice = new Notice();
        notice.setTitle(request.title().trim());
        notice.setContent(request.content());
        notice.setSummary(autoSummary(request.content()));
        notice.setPublisher(publisherName(publisher));
        notice.setPublisherType(publisher.type());
        notice.setPublisherDept(publisher.dept());
        notice.setTopFlag(0);
        notice.setStatus(1);
        notice.setCreateTime(LocalDateTime.now());
        notice.setUpdateTime(LocalDateTime.now());
        adminNoticeMapper.insert(notice);
        return notice;
    }

    /** 发布者展示名：ADMIN=系统管理员 / DEPT=部门展示名（复合键=区县+分类，兼容历史纯code） / STREET=街道办名（region） */
    private String publisherName(Publisher publisher) {
        if ("ADMIN".equals(publisher.type())) {
            return "系统管理员";
        }
        if ("DEPT".equals(publisher.type())) {
            return deptDisplayName(publisher.dept());
        }
        // STREET：当前登录街道办账号的辖区名
        LoginUser u = UserContext.get();
        return u == null || u.region() == null ? "街道办" : u.region();
    }

    /** 编辑时标题兜底（未传沿用原值） */
    private String requireTitle(NoticeUpsertRequest request, Notice exist) {
        if (request.title() == null || request.title().isBlank()) {
            return exist.getTitle();
        }
        return request.title().trim();
    }

    /**
     * 编辑联动：主表内容更新 → 三收件箱内容传播（保留已读）→ 接收目标差异同步
     * （新增目标插副本、移除目标删副本；未变目标保留已读状态）
     */
    private void applyEdit(Long id, NoticeUpsertRequest request, Notice exist,
                           List<NoticeTarget> newTargets, boolean resetRead) {
        Notice notice = new Notice();
        notice.setId(id);
        notice.setTitle(requireTitle(request, exist));
        notice.setContent(request.content());
        notice.setSummary(autoSummary(request.content()));
        if (resetRead) {
            // 重新发布：主表重置为展示中并刷新发布时间（编辑场景不触碰 status/create_time，保持原口径）
            notice.setStatus(1);
            notice.setCreateTime(LocalDateTime.now());
        }
        adminNoticeMapper.update(notice);
        // 内容传播到已存在的收件箱副本
        deptNoticeMapper.propagateEdit(id, notice.getTitle(), notice.getContent(), notice.getSummary());
        streetNoticeMapper.propagateEdit(id, notice.getTitle(), notice.getContent(), notice.getSummary());
        citizenNoticeMapper.propagateEdit(id, notice.getTitle(), notice.getContent(), notice.getSummary());
        if (resetRead) {
            // 重新发布：全量重建（删旧副本再扇出）→ 已读清零
            refreshFanOut(id, newTargets);
            return;
        }
        // 差异同步：新增目标插副本，移除目标删副本
        Notice fresh = requireNotice(id);
        List<NoticeTarget> oldTargets = targetsOf(id);
        Set<String> oldKeys = targetKeys(oldTargets);
        Set<String> newKeys = targetKeys(newTargets);
        for (NoticeTarget t : newTargets) {
            if (!oldKeys.contains(targetKey(t))) {
                fanOutOne(fresh, t);
            }
        }
        for (NoticeTarget t : oldTargets) {
            if (!newKeys.contains(targetKey(t))) {
                deleteTarget(id, t);
            }
        }
    }

    /** 目标 key（"TYPE:code"） */
    private String targetKey(NoticeTarget t) {
        return t.getTargetType() + ":" + (t.getTargetCode() == null ? "" : t.getTargetCode());
    }

    private Set<String> targetKeys(List<NoticeTarget> targets) {
        return targets.stream().map(this::targetKey).collect(Collectors.toSet());
    }

    /** 删除单个目标对应收件箱副本 */
    private void deleteTarget(Long noticeId, NoticeTarget t) {
        switch (t.getTargetType()) {
            case "DEPT" -> deptNoticeMapper.deleteByNoticeAndCode(noticeId, t.getTargetCode());
            case "STREET" -> streetNoticeMapper.deleteByNoticeAndName(noticeId, t.getTargetCode());
            case "PUBLIC" -> citizenNoticeMapper.deleteByNoticeId(noticeId);
            default -> { }
        }
    }

    /** 下架联动：主表 + 三收件箱 status=0 */
    private void offShelf(Notice exist) {
        Notice notice = new Notice();
        notice.setId(exist.getId());
        notice.setStatus(0);
        adminNoticeMapper.update(notice);
        deptNoticeMapper.updateStatusByNoticeId(exist.getId(), 0);
        streetNoticeMapper.updateStatusByNoticeId(exist.getId(), 0);
        citizenNoticeMapper.updateStatusByNoticeId(exist.getId(), 0);
    }

    /** 重新发布联动：主表（status=1、发布时间刷新）→ 三收件箱重建（已读清零） */
    private void republish(Notice exist, NoticeUpsertRequest request, List<NoticeTarget> targets) {
        applyEdit(exist.getId(), request, exist, targets, true);
    }

    /** 全量重建收件箱（重发）：删三收件箱副本 → 按目标重新扇出 */
    private void refreshFanOut(Long noticeId, List<NoticeTarget> targets) {
        deptNoticeMapper.deleteByNoticeId(noticeId);
        streetNoticeMapper.deleteByNoticeId(noticeId);
        citizenNoticeMapper.deleteByNoticeId(noticeId);
        Notice fresh = requireNotice(noticeId);
        for (NoticeTarget t : targets) {
            fanOutOne(fresh, t);
        }
    }

    /** 按目标扇出全部收件箱副本 */
    private void fanOut(Notice notice, List<NoticeTarget> targets) {
        for (NoticeTarget t : targets) {
            fanOutOne(notice, t);
        }
    }

    /** 扇出单个目标副本（部门目标按 56 口径展开：纯分类code → 7 区县各一行，各区县已读独立） */
    private void fanOutOne(Notice notice, NoticeTarget t) {
        LocalDateTime now = LocalDateTime.now();
        switch (t.getTargetType()) {
            case "DEPT" -> {
                for (String deptCode : expandDeptTarget(t.getTargetCode())) {
                    DeptNotice d = new DeptNotice();
                    fill(d, notice, now);
                    d.setDeptCode(deptCode);
                    d.setIsRead(0);
                    deptNoticeMapper.insert(d);
                }
            }
            case "STREET" -> {
                StreetNotice s = new StreetNotice();
                fill(s, notice, now);
                s.setStreetName(t.getTargetCode());
                s.setIsRead(0);
                streetNoticeMapper.insert(s);
            }
            case "PUBLIC" -> {
                CitizenNotice c = new CitizenNotice();
                fill(c, notice, now);
                citizenNoticeMapper.insert(c);
            }
            default -> { }
        }
    }

    private void fill(DeptNotice d, Notice n, LocalDateTime now) {
        d.setNoticeId(n.getId());
        d.setTitle(n.getTitle());
        d.setContent(n.getContent());
        d.setSummary(n.getSummary());
        d.setPublisher(n.getPublisher());
        d.setPublisherType(n.getPublisherType());
        d.setPublisherDept(n.getPublisherDept());
        d.setTopFlag(n.getTopFlag());
        d.setStatus(n.getStatus());
        d.setCreateTime(now);
    }

    private void fill(StreetNotice s, Notice n, LocalDateTime now) {
        s.setNoticeId(n.getId());
        s.setTitle(n.getTitle());
        s.setContent(n.getContent());
        s.setSummary(n.getSummary());
        s.setPublisher(n.getPublisher());
        s.setPublisherType(n.getPublisherType());
        s.setPublisherDept(n.getPublisherDept());
        s.setTopFlag(n.getTopFlag());
        s.setStatus(n.getStatus());
        s.setCreateTime(now);
    }

    private void fill(CitizenNotice c, Notice n, LocalDateTime now) {
        c.setNoticeId(n.getId());
        c.setTitle(n.getTitle());
        c.setContent(n.getContent());
        c.setSummary(n.getSummary());
        c.setPublisher(n.getPublisher());
        c.setPublisherType(n.getPublisherType());
        c.setPublisherDept(n.getPublisherDept());
        c.setTopFlag(n.getTopFlag());
        c.setStatus(n.getStatus());
        c.setCreateTime(now);
    }

    /** 市民公开副本 → Notice（公众端 VO 形状对齐主表字段） */
    private Notice toNotice(CitizenNotice c) {
        Notice n = new Notice();
        n.setId(c.getNoticeId());
        n.setTitle(c.getTitle());
        n.setContent(c.getContent());
        n.setSummary(c.getSummary());
        n.setPublisher(c.getPublisher());
        n.setPublisherType(c.getPublisherType());
        n.setPublisherDept(c.getPublisherDept());
        n.setTopFlag(c.getTopFlag());
        n.setStatus(c.getStatus());
        n.setCreateTime(c.getCreateTime());
        return n;
    }

    /** 审计留痕（按当前操作人自动路由三端审计日志表） */
    private void audit(String summary) {
        LoginUser u = UserContext.get();
        String operator = u == null ? "SYSTEM" : u.userType() + "-" + u.jobNo();
        auditLogService.recordOperation(operator, "notice", summary);
    }

    /** 街道办端校验：仅可操作本街道名义发布的通知（publisher_type=STREET） */
    private Notice requireStreetOwn(LoginUser user, Long id) {
        Notice exist = requireNotice(id);
        if (!"STREET".equals(exist.getPublisherType()) || exist.getPublisherDept() != null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅可操作本街道名义发布的通知");
        }
        return exist;
    }

    /** 部门端校验：仅可操作本部门名义发布的通知（复合键=本账号区县:分类；兼容历史纯分类code 的名义） */
    private Notice requireDeptOwn(LoginUser user, Long id) {
        Notice exist = requireNotice(id);
        String dept = exist.getPublisherDept();
        boolean owned = "DEPT".equals(exist.getPublisherType())
                && (deptKeyOf(user).equals(dept)
                || (dept != null && !dept.contains(":") && user.userType().equals(dept)));
        if (!owned) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅可操作本部门名义发布的通知");
        }
        return exist;
    }

    private Notice requireNotice(Long id) {
        Notice exist = adminNoticeMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "通知不存在");
        }
        return exist;
    }

    /** 列表装饰：从三收件箱反查接收对象 → 拼 targetSummary（部门/街道全选时折叠） */
    private List<NoticeVO> decorateWithTargets(List<Notice> notices) {
        if (notices.isEmpty()) {
            return List.of();
        }
        Map<String, String> deptNames = classifyPrimaryMapper.selectEnabled().stream()
                .collect(Collectors.toMap(ClassifyPrimary::getCode, ClassifyPrimary::getName, (a, b) -> a));
        Set<String> allStreets = allStreetNames();
        int deptTotal = deptTargetCount();
        List<NoticeVO> vos = new ArrayList<>();
        for (Notice n : notices) {
            NoticeVO vo = new NoticeVO();
            vo.setId(n.getId());
            vo.setTitle(n.getTitle());
            vo.setContent(n.getContent());
            vo.setSummary(n.getSummary());
            vo.setPublisher(n.getPublisher());
            vo.setPublisherType(n.getPublisherType());
            vo.setPublisherDept(n.getPublisherDept());
            vo.setPublisherDeptName(deptDisplayName(n.getPublisherDept()));
            vo.setTopFlag(n.getTopFlag());
            vo.setStatus(n.getStatus());
            vo.setCreateTime(n.getCreateTime());
            vo.setUpdateTime(n.getUpdateTime());
            List<NoticeTarget> targets = targetsOf(n.getId());
            vo.setTargets(targets);
            vo.setTargetSummary(buildTargetSummary(targets, deptNames, deptTotal, allStreets));
            vo.setIsRead(1);
            vos.add(vo);
        }
        return vos;
    }

    /** 从三收件箱反查接收对象（管理端编辑回填/接收对象摘要） */
    private List<NoticeTarget> targetsOf(Long noticeId) {
        List<NoticeTarget> targets = new ArrayList<>();
        for (String code : adminNoticeMapper.selectTargetDeptCodes(noticeId)) {
            NoticeTarget t = new NoticeTarget();
            t.setNoticeId(noticeId);
            t.setTargetType("DEPT");
            t.setTargetCode(code);
            targets.add(t);
        }
        for (String name : adminNoticeMapper.selectTargetStreetNames(noticeId)) {
            NoticeTarget t = new NoticeTarget();
            t.setNoticeId(noticeId);
            t.setTargetType("STREET");
            t.setTargetCode(name);
            targets.add(t);
        }
        if (adminNoticeMapper.countCitizenCopy(noticeId) > 0) {
            NoticeTarget t = new NoticeTarget();
            t.setNoticeId(noticeId);
            t.setTargetType("PUBLIC");
            t.setTargetCode(null);
            targets.add(t);
        }
        return targets;
    }

    /** 目标集合摘要（管理端列表/发布弹窗回显） */
    private String targetSummaryOf(List<NoticeTarget> targets) {
        Map<String, String> deptNames = classifyPrimaryMapper.selectEnabled().stream()
                .collect(Collectors.toMap(ClassifyPrimary::getCode, ClassifyPrimary::getName, (a, b) -> a));
        return buildTargetSummary(targets, deptNames, deptTargetCount(), allStreetNames());
    }

    /** 接收对象摘要：如"全部部门、海港区文化路街道、市民公开"（部门/街道全选时折叠；56口径部门条目显示区县+分类名） */
    private String buildTargetSummary(List<NoticeTarget> targets,
                                      Map<String, String> deptNames, int deptTotal, Set<String> allStreets) {
        List<String> parts = new ArrayList<>();
        List<NoticeTarget> depts = targets.stream().filter(t -> "DEPT".equals(t.getTargetType())).toList();
        List<NoticeTarget> streets = targets.stream().filter(t -> "STREET".equals(t.getTargetType())).toList();
        boolean hasPublic = targets.stream().anyMatch(t -> "PUBLIC".equals(t.getTargetType()));
        if (depts.size() >= deptTotal && deptTotal > 0) {
            parts.add("全部部门");
        } else {
            depts.forEach(t -> parts.add(deptDisplayName(t.getTargetCode())));
        }
        if (streets.size() >= allStreets.size() && !allStreets.isEmpty()) {
            parts.add("全部街道办");
        } else {
            streets.forEach(t -> parts.add(t.getTargetCode()));
        }
        if (hasPublic) {
            parts.add("市民公开");
        }
        return String.join("、", parts);
    }

    /** 收到列表补充发布者中文名（发布者下拉筛选/展示用；56口径复合键显示区县+分类名） */
    private void decoratePublisherName(List<NoticeVO> vos) {
        vos.forEach(vo -> vo.setPublisherDeptName(deptDisplayName(vo.getPublisherDept())));
    }

    /** 全部街道办选项（按区县分组，发布弹窗两级选择用，与工单分派同源） */
    private List<Map<String, Object>> groupedStreetOptions() {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (SysRegion county : regionService.listCounties()) {
            List<String> streets = new ArrayList<>();
            for (SysRegion child : regionService.listChildren(county.getRegionCode())) {
                streets.add(child.getRegionName());
            }
            if (!streets.isEmpty()) {
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("county", county.getRegionName());
                group.put("streets", streets);
                groups.add(group);
            }
        }
        return groups;
    }

    /** 全部街道办名集合（sys_region 乡镇级启用区划） */
    private Set<String> allStreetNames() {
        Set<String> names = new HashSet<>();
        for (SysRegion county : regionService.listCounties()) {
            for (SysRegion child : regionService.listChildren(county.getRegionCode())) {
                names.add(child.getRegionName());
            }
        }
        return names;
    }

    /** 部门 code → 中文名（classify_primary） */
    private String deptName(String code) {
        ClassifyPrimary p = code == null ? null : classifyPrimaryMapper.selectByCode(code);
        return p == null || p.getName() == null || p.getName().isBlank() ? code : p.getName();
    }

    /** 摘要自动截正文前 100 字（无需手填） */
    private String autoSummary(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String plain = content.strip();
        return plain.length() <= 100 ? plain : plain.substring(0, 100);
    }

    /** 日期区间筛选：[start 00:00, end 次日00:00)（含头含尾整天）。只填一头按开放区间；两者都空不过滤 */
    private LocalDateTime[] dateRange(String startDate, String endDate) {
        if ((startDate == null || startDate.isBlank()) && (endDate == null || endDate.isBlank())) {
            return new LocalDateTime[]{null, null};
        }
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = (startDate == null || startDate.isBlank()) ? null
                    : LocalDate.parse(startDate.trim()).atStartOfDay();
            end = (endDate == null || endDate.isBlank()) ? null
                    : LocalDate.parse(endDate.trim()).plusDays(1).atStartOfDay();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "日期格式不合法，应为 yyyy-MM-dd");
        }
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }
        return new LocalDateTime[]{start, end};
    }
}
