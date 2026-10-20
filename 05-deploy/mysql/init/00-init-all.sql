SET NAMES utf8mb4;
-- ============================================================================
-- 12345政务热线工单智能分类与诉求预判系统 — 建库脚本（仅建表，不含数据）
-- 引擎：MySQL 8.0 / utf8mb4
-- 说明：本脚本只创建数据库和所有表结构；数据插入见 01-test-data.sql
-- ============================================================================

CREATE DATABASE IF NOT EXISTS hotline_12345 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE hotline_12345;


-- ----------------------------------------------------------------------------
-- 1. 用户表（内部工作人员，密码 bcrypt 加密；需求 表2 / API 5.2）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    job_no       VARCHAR(20)  NOT NULL COMMENT '工号（6-20位字母数字组合，唯一）',
    name         VARCHAR(50)  NOT NULL COMMENT '姓名',
    phone        VARCHAR(11)  DEFAULT NULL COMMENT '手机号（11位）',
    email        VARCHAR(100) DEFAULT NULL COMMENT '邮箱（注册邮箱验证码功能）',
    password     VARCHAR(100) NOT NULL COMMENT '密码（bcrypt加盐哈希，明文不落库）',
    company      VARCHAR(100) DEFAULT NULL COMMENT '所属单位',
    user_type    VARCHAR(20)  NOT NULL COMMENT '归属类型：ADMIN/STREET/URBAN/TRAFFIC/MARKET/ENVIRONMENT/HOUSING/LABOR/PUBLIC/HEALTH',
    region       VARCHAR(100) DEFAULT NULL COMMENT '对应辖区/部门（街道办=街道名；部门=区县+部门拼接名，如海港区城管部门）',
    region_code  VARCHAR(20)  DEFAULT NULL COMMENT '区划编码（街道办=街道级；部门=区县级，均对应 sys_region.region_code）',
    status       VARCHAR(20)  NOT NULL DEFAULT 'ENABLED'  COMMENT '账号状态：ENABLED/DISABLED（与审核状态相互独立）',
    audit_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING'  COMMENT '审核状态：PENDING/APPROVED/REJECTED',
    pwd_version  INT          NOT NULL DEFAULT 0 COMMENT '密码版本（改密/重置+1，用于旧Token失效；DB为事实源，Redis仅缓存）',
    is_deleted   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常/1已删除',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_no (job_no),
    UNIQUE KEY uk_phone (phone),
    KEY idx_email (email),
    KEY idx_user_type (user_type),
    KEY idx_audit_status (audit_status)
) ENGINE = InnoDB COMMENT = '用户表（内部工作人员，含政府部门各岗位人员；密码bcrypt加密）';

-- ----------------------------------------------------------------------------
-- 2. 账号审核记录表（需求 表1/表4 / API 5.3-5.7）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_account_audit;
CREATE TABLE sys_account_audit (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id       BIGINT       NOT NULL COMMENT '申请人ID（sys_user.id）',
    audit_status  VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '审核状态：PENDING/APPROVED/REJECTED',
    audit_user_id BIGINT       DEFAULT NULL COMMENT '审核人ID',
    audit_time    DATETIME     DEFAULT NULL COMMENT '审核时间',
    reject_reason VARCHAR(500) DEFAULT NULL COMMENT '驳回原因',
    apply_time    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_audit_status (audit_status),
    KEY idx_apply_time (apply_time)
) ENGINE = InnoDB COMMENT = '账号审核记录表';

-- ----------------------------------------------------------------------------
-- 3. 登录日志表（需求 表2/表17 / API 9.11）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_login_log;
CREATE TABLE sys_login_log (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id      BIGINT      DEFAULT NULL COMMENT '用户ID（市民登录为NULL）',
    login_method VARCHAR(20) NOT NULL COMMENT '登录方式：JOB_NO_PASSWORD/SMS_CODE',
    ip           VARCHAR(50) DEFAULT NULL COMMENT '登录IP',
    device       VARCHAR(200) DEFAULT NULL COMMENT '设备信息',
    login_time   DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    result       VARCHAR(20) NOT NULL COMMENT '登录结果：SUCCESS/FAIL',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_login_time (login_time)
) ENGINE = InnoDB COMMENT = '登录日志表';

-- ----------------------------------------------------------------------------
-- 市民账号表（市民自助注册：手机号/邮箱登录、个人资料、诉求提交默认值）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS citizen_account;
CREATE TABLE citizen_account (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    phone           VARCHAR(11)  NOT NULL COMMENT '手机号（登录账号）',
    password        VARCHAR(100) NOT NULL COMMENT '登录密码（bcrypt）',
    name            VARCHAR(50)  NOT NULL COMMENT '姓名',
    email           VARCHAR(100) DEFAULT NULL COMMENT '邮箱（邮箱登录账号）',
    district_code   VARCHAR(20)  DEFAULT NULL COMMENT '辖区区划编码（sys_region 区县级）',
    district        VARCHAR(50)  DEFAULT NULL COMMENT '辖区名称',
    street_code     VARCHAR(20)  DEFAULT NULL COMMENT '街道/镇/乡区划编码',
    street          VARCHAR(50)  DEFAULT NULL COMMENT '街道/镇/乡名称',
    address_detail  VARCHAR(200) DEFAULT NULL COMMENT '详细住址（选填）',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '账号创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone),
    UNIQUE KEY uk_email (email)
) ENGINE = InnoDB COMMENT = '市民账号表';

-- ----------------------------------------------------------------------------
-- 3.1 市民端诉求提交记录表（四端数据解耦：市民端独立落库）
--     市民提交诉求时，除写入管理端诉求域（appeal_parent）外，同步在本表落一条独立记录；
--     市民端"我的诉求"等展示可只读本表，与管理端诉求域解耦。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS citizen_appeal;
CREATE TABLE citizen_appeal (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    appeal_no       VARCHAR(50)  NOT NULL COMMENT '诉求编号（与 appeal_parent.appeal_no 一致，全局唯一）',
    parent_id       BIGINT       DEFAULT NULL COMMENT '管理端诉求主表ID（appeal_parent.id，同事务写入必然存在）',
    citizen_user_id BIGINT       DEFAULT NULL COMMENT '提交市民（citizen_account.id，短信登录账号）',
    citizen_name    VARCHAR(50)  DEFAULT NULL COMMENT '市民姓名（可空，市民提交时不强制）',
    citizen_phone   VARCHAR(11)  NOT NULL COMMENT '市民手机号（登录账号，必填）',
    title           VARCHAR(200) DEFAULT NULL COMMENT '诉求标题（AI标题生成后可回填；提交时为空）',
    content         TEXT         DEFAULT NULL COMMENT '诉求内容',
    province        VARCHAR(50)  DEFAULT NULL COMMENT '省级行政区划',
    city            VARCHAR(50)  DEFAULT NULL COMMENT '市级行政区划',
    district        VARCHAR(50)  DEFAULT NULL COMMENT '区级行政区划',
    street          VARCHAR(50)  DEFAULT NULL COMMENT '街道（乡镇）',
    community       VARCHAR(50)  DEFAULT NULL COMMENT '社区',
    address_detail  VARCHAR(200) DEFAULT NULL COMMENT '详细地址补充',
    broad_flag      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否宽泛诉求：1 宽泛（区县级）/ 0 具体',
    submit_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_appeal_no (appeal_no),
    KEY idx_phone (citizen_phone)
) ENGINE = InnoDB COMMENT = '市民端诉求提交记录表（市民端独立落库，与管理端诉求域解耦）';

-- ----------------------------------------------------------------------------
-- 4. 角色表（需求 表3：系统管理员/城管/物业/民生部门人员/街道办人员/审核专员/督办专员）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_role;
CREATE TABLE sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    code        VARCHAR(50)  NOT NULL COMMENT '角色编码',
    name        VARCHAR(50)  NOT NULL COMMENT '角色名称',
    scope       VARCHAR(100) DEFAULT NULL COMMENT '数据权限范围（辖区/分类）',
    description VARCHAR(255) DEFAULT NULL COMMENT '权限描述',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB COMMENT = '角色表（政府部门不同权限角色）';

-- ----------------------------------------------------------------------------
-- 5. 菜单权限表（需求 表3）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_menu;
CREATE TABLE sys_menu (
    id        BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    code      VARCHAR(50) NOT NULL COMMENT '菜单编码',
    name      VARCHAR(50) NOT NULL COMMENT '菜单名称',
    parent_id BIGINT      DEFAULT 0 COMMENT '父级菜单',
    route     VARCHAR(200) DEFAULT NULL COMMENT '路由地址',
    perm      VARCHAR(100) DEFAULT NULL COMMENT '按钮/操作权限标识',
    sort_order INT        DEFAULT 0 COMMENT '排序',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB COMMENT = '菜单权限表';

-- ----------------------------------------------------------------------------
-- 6/7. 用户角色关联表 / 角色权限关联表（需求 表3）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_user_role;
CREATE TABLE sys_user_role (
    id      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE = InnoDB COMMENT = '用户角色关联表';

DROP TABLE IF EXISTS sys_role_menu;
CREATE TABLE sys_role_menu (
    id      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单/权限ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_menu (role_id, menu_id)
) ENGINE = InnoDB COMMENT = '角色权限关联表';

-- ----------------------------------------------------------------------------
-- 8. 审计日志表·三端拆分（需求 19/20 / API 8.6）
--    【安全红线】仅 INSERT，禁止 UPDATE/DELETE，永久留存
--    三端数据解耦：admin_audit_log（管理端操作）/ dept_audit_log（部门端操作+归属部门的收到工单）
--    / street_audit_log（街道办端操作+归属街道办的收到工单）。「收到工单」类日志写入接收端表。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS admin_audit_log;
CREATE TABLE admin_audit_log (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    log_type       VARCHAR(20)   NOT NULL COMMENT '日志类型：LOGIN/OPERATION/ERROR/AI_GENERATION',
    operator       VARCHAR(50)   DEFAULT NULL COMMENT '操作人',
    module         VARCHAR(100)  DEFAULT NULL COMMENT '操作模块',
    content_type   VARCHAR(50)   DEFAULT NULL COMMENT '内容类型（AI生成：CLASSIFY/DETECT/SUGGEST/PREDICT/WARNING/CHAT 等）',
    summary        VARCHAR(1000) DEFAULT NULL COMMENT '内容摘要',
    confidence     DECIMAL(5, 4) DEFAULT NULL COMMENT '置信度（AI生成）',
    decision_basis VARCHAR(1000) DEFAULT NULL COMMENT '决策依据（AI生成）',
    model          VARCHAR(100)  DEFAULT NULL COMMENT '模型标识（AI生成）',
    target_type    VARCHAR(50)   DEFAULT NULL COMMENT '日志归属端（部门代号/STREET，收到工单类日志按此过滤展示）',
    create_time    DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_log_type (log_type),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT = '管理端审计日志表（仅INSERT，禁止UPDATE/DELETE）';

DROP TABLE IF EXISTS dept_audit_log;
CREATE TABLE dept_audit_log LIKE admin_audit_log;
ALTER TABLE dept_audit_log COMMENT = '部门端审计日志表（与管理端审计日志表同构；部门端操作与归属部门的「收到工单」日志）';

DROP TABLE IF EXISTS street_audit_log;
CREATE TABLE street_audit_log LIKE admin_audit_log;
ALTER TABLE street_audit_log COMMENT = '街道办端审计日志表（与管理端审计日志表同构；街道办端操作与归属街道办的「收到工单」日志）';

-- ----------------------------------------------------------------------------
-- 9. 诉求附件表（需求 表5 / API 4.3）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS appeal_attachment;
CREATE TABLE appeal_attachment (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    appeal_id   BIGINT      NOT NULL COMMENT '诉求ID',
    file_path   VARCHAR(500) NOT NULL COMMENT '文件路径',
    file_type   VARCHAR(20) NOT NULL COMMENT '文件类型：IMAGE/VIDEO',
    ocr_text    TEXT        DEFAULT NULL COMMENT 'OCR识别文本',
    scene_tags  VARCHAR(500) DEFAULT NULL COMMENT 'AI场景标签（逗号分隔）',
    upload_time DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    KEY idx_appeal_id (appeal_id)
) ENGINE = InnoDB COMMENT = '诉求附件表（含AI解析结果）';

-- ----------------------------------------------------------------------------
-- 9.1 父诉求表（appeal 拆表后的父表）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS appeal_parent;
CREATE TABLE appeal_parent (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    appeal_no           VARCHAR(50)   NOT NULL COMMENT '诉求编号（AP+时间戳+序号）',
    title               VARCHAR(200)  DEFAULT NULL COMMENT '诉求标题（市民提交时为空，由 AI 总结生成）',
    content             TEXT          NOT NULL COMMENT '详细诉求内容（原始全文）',
    category            VARCHAR(20)   DEFAULT NULL COMMENT '诉求类别（8大类code或OTHER，市民提交自带）',
    citizen_name        VARCHAR(50)   DEFAULT NULL COMMENT '市民姓名（提诉人）',
    citizen_phone       VARCHAR(11)   NOT NULL COMMENT '市民手机号（提诉人）',
    province            VARCHAR(50)   DEFAULT NULL COMMENT '省级行政区划',
    city                VARCHAR(50)   DEFAULT NULL COMMENT '市级行政区划',
    district            VARCHAR(50)   DEFAULT NULL COMMENT '区级行政区划',
    district_code       VARCHAR(20)   DEFAULT NULL COMMENT '区县编码（sys_region.region_code，level=COUNTY）',
    street              VARCHAR(50)   DEFAULT NULL COMMENT '街道（乡镇）',
    street_code         VARCHAR(20)   DEFAULT NULL COMMENT '街道/镇/乡编码（sys_region.region_code，level=STREET/TOWN/TOWNSHIP）',
    community           VARCHAR(50)   DEFAULT NULL COMMENT '社区',
    address_detail      VARCHAR(200)  DEFAULT NULL COMMENT '详细地址补充',
    region              VARCHAR(500)  DEFAULT NULL COMMENT '完整地址（district+street+address_detail拼接，展示/分发用）',
    broad_flag          TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '宽泛诉求开关',
    status              VARCHAR(30)   NOT NULL DEFAULT 'TITLE_PENDING' COMMENT '父诉求状态：TITLE_PENDING(待AI生成标题)/TITLE_GENERATING(AI标题生成中)/TITLE_GENERATED(待拆分)/SPLITTING(AI拆分中)/CHILDREN_GENERATED(已拆分)/WITHDRAWN',
    child_count         INT           NOT NULL DEFAULT 0 COMMENT '子诉求数量（冗余计数）',
    is_deleted          TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '软删除标记 0=正常 1=已删除（删除保留历史，列表/查询过滤）',
    create_time         DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    update_time         DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_appeal_no (appeal_no),
    KEY idx_phone (citizen_phone),
    KEY idx_status (status),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT = '父诉求表（市民一次提交的原始诉求，可拆分为多个子诉求）';

-- ----------------------------------------------------------------------------
-- 9.2 子诉求表（AI拆分出的单个事件，用于生成/归并工单的最小单位）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS appeal_child;
CREATE TABLE appeal_child (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    appeal_no        VARCHAR(50)   NOT NULL COMMENT '诉求编号（AS+时间戳+序号）',
    parent_id        BIGINT        NOT NULL COMMENT '父诉求ID（appeal_parent.id）',
    title            VARCHAR(200)  DEFAULT NULL COMMENT '事件标题（入库时为空，由 AI 按子诉求内容总结生成）',
    content          TEXT          NOT NULL COMMENT '事件内容（加工后正式文本）',
    category         VARCHAR(20)   DEFAULT NULL COMMENT '诉求类别（8大类code或OTHER，继承父诉求，AI审核/分类可能纠正）',
    sub_category     VARCHAR(50)   DEFAULT NULL COMMENT 'AI分类二级标签（队列1落库，建单/归并依据）',
    confidence       DECIMAL(6,4)  DEFAULT NULL COMMENT 'AI分类置信度0-1',
    need_review      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '需人工复核 0=否 1=是',
    clean_log_id     BIGINT        DEFAULT NULL COMMENT '清洗日志ID（admin_audit_log.id）',
    citizen_name     VARCHAR(50)   DEFAULT NULL COMMENT '市民姓名（冗余自父诉求）',
    citizen_phone    VARCHAR(11)   NOT NULL COMMENT '市民手机号（冗余自父诉求）',
    province         VARCHAR(50)   DEFAULT NULL COMMENT '省级行政区划',
    city             VARCHAR(50)   DEFAULT NULL COMMENT '市级行政区划',
    district         VARCHAR(50)   DEFAULT NULL COMMENT '区级行政区划',
    district_code    VARCHAR(20)   DEFAULT NULL COMMENT '区县编码（sys_region.region_code，level=COUNTY）',
    street           VARCHAR(50)   DEFAULT NULL COMMENT '街道（乡镇）',
    street_code      VARCHAR(20)   DEFAULT NULL COMMENT '街道/镇/乡编码（sys_region.region_code，level=STREET/TOWN/TOWNSHIP）',
    community        VARCHAR(50)   DEFAULT NULL COMMENT '社区',
    address_detail   VARCHAR(200)  DEFAULT NULL COMMENT '详细地址补充',
    region           VARCHAR(500)  DEFAULT NULL COMMENT '完整地址（district+street+address_detail拼接，展示/分发用）',
    broad_flag       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '宽泛诉求开关',
    status           VARCHAR(30)   NOT NULL DEFAULT 'TITLE_PENDING' COMMENT '子诉求状态：TITLE_PENDING(待AI生成标题)/TITLE_GENERATING(AI标题生成中)/AUDIT_PENDING(待AI审核)/AUDITING(AI审核中，只审一级分类)/CORRECTING(AI类别纠正中，纠正一级)/CLASSIFYING(AI一级分类)/CLASSIFYING_SUB(AI二级分类)/READY(待生成工单)/ORDERING(生成工单中)/ORDERED(已生成工单)',
    is_deleted       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '软删除标记 0=正常 1=已删除（删除保留历史，列表/查询过滤）',
    create_time      DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_appeal_no (appeal_no),
    KEY idx_parent_id (parent_id),
    KEY idx_status (status),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT = '子诉求表（AI拆分出的单个事件，用于生成/归并工单的最小单位）';


-- ----------------------------------------------------------------------------
-- 11. 工单表·三端拆分（需求 表6/表19/表21/表23 / API 6.2）
--     数据表解耦：admin_work_order（管理端全量权威副本，建单即写入）
--     / dept_work_order（部门端副本，管理端分派后写入，department 清空时删除）
--     / street_work_order（街道办端副本，部门分派街道办后写入，street_office 清空时删除）。
--     【副本不变式】dept_work_order 有行 ⟺ department 非空；street_work_order 有行 ⟺ street_office 非空。
--     三表共用同一主键 id（管理端建单生成，副本携带同 id），order_no 全局唯一。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS admin_work_order;
CREATE TABLE admin_work_order (
    id                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID（三端副本共用同一id）',
    order_no                VARCHAR(50)  NOT NULL COMMENT '工单编号（WO+日期+序号，全局唯一）',
    source_appeal_id        BIGINT       DEFAULT NULL COMMENT '来源诉求ID',
    title                   VARCHAR(200) NOT NULL COMMENT '诉求标题',
    content                 TEXT         DEFAULT NULL COMMENT '诉求内容',
    category                VARCHAR(20)  DEFAULT NULL COMMENT '一级分类：URBAN/TRAFFIC/MARKET/ENVIRONMENT/HOUSING/LABOR/PUBLIC/HEALTH',
    sub_category            VARCHAR(50)  DEFAULT NULL COMMENT '二级分类标签',
    level                   VARCHAR(10)  NOT NULL DEFAULT '三级' COMMENT '工单等级：一级/二级/三级',
    attention_count         INT          NOT NULL DEFAULT 1 COMMENT '关注度（默认1，同源合并累加）',
    confidence              DECIMAL(5, 4) DEFAULT NULL COMMENT 'AI置信度（0~1）',
    hit_keywords            VARCHAR(500) DEFAULT NULL COMMENT '命中关键词（逗号分隔）',
    need_review             TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '低置信度待人工复核标记',
    region                  VARCHAR(100) DEFAULT NULL COMMENT '所属辖区',
    county_code             VARCHAR(20)  DEFAULT NULL COMMENT '区县编码（sys_region.region_code，level=COUNTY）',
    county_name             VARCHAR(50)  DEFAULT NULL COMMENT '区县名称（用于街道办匹配与列表展示）',
    department              VARCHAR(50)  DEFAULT NULL COMMENT '分派部门（区县+部门拼接名，如海港区城管部门；历史数据为纯部门名）',
    street_office           VARCHAR(100) DEFAULT NULL COMMENT '承办街道办',
    dept_receive_time       DATETIME     DEFAULT NULL COMMENT '工单到达本部门时间（管理端分派时写入，部门端未读红点基准）',
    street_receive_time     DATETIME     DEFAULT NULL COMMENT '工单到达街道办时间（部门分派时写入，街道办端未读红点基准）',
    street_code             VARCHAR(20)  DEFAULT NULL COMMENT '街道/镇/乡编码（匹配街道办账号用）',
    street_name             VARCHAR(50)  DEFAULT NULL COMMENT '街道/镇/乡名称',
    address_detail          VARCHAR(200) DEFAULT NULL COMMENT '详细地址（人工录入工单/诉求详细地址）',
    is_broad                TINYINT(1)   DEFAULT NULL COMMENT '是否宽泛工单：1宽泛（区县级）/0具体（NULL=历史数据未区分）',
    status                  VARCHAR(30)  NOT NULL DEFAULT 'PENDING_DISPATCH' COMMENT '流转状态（见 API 14.1）：PENDING_DISPATCH→PENDING_DEPT_PROCESS→PENDING_DEPT_ASSIGN→PENDING_STREET_PROCESS→STREET_DONE→DEPT_AUDITED→CLOSED→EVALUATED（副本表状态与主表保持同步）',
    same_source_order_id    BIGINT       DEFAULT NULL COMMENT '同源工单ID（供重复研判与关注度统计）',
    event_type              VARCHAR(50)  DEFAULT NULL COMMENT '事件类型（去重归并键，由子诉求event_type带入）',
    -- ---- 街道办处置字段（需求 表19）----
    dispose_plan            TEXT         DEFAULT NULL COMMENT '处置方案（≤2000字）',
    dispose_result          TEXT         DEFAULT NULL COMMENT '处置结果（≤2000字）',
    dispose_tag             VARCHAR(20)  DEFAULT NULL COMMENT '处置标签：RESOLVED/PART_RESOLVED/UNRESOLVED/TRANSFERRED',
    dispose_time            DATETIME     DEFAULT NULL COMMENT '实际处置时间',
    dispose_user_id         BIGINT       DEFAULT NULL COMMENT '处置人ID',
    dispose_user_name       VARCHAR(50)  DEFAULT NULL COMMENT '处置人姓名（冗余存储）',
    deadline                DATETIME     DEFAULT NULL COMMENT '处理截止时间（根据工单等级自动计算，普通5个工作日/紧急2个工作日）',
    is_overdue              TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否超期（默认0，超时自动标记，状态不变）',
    delay_count             INT          NOT NULL DEFAULT 0 COMMENT '已延期次数（默认0，上限2次）',
    street_return_count     INT          NOT NULL DEFAULT 0 COMMENT '街道办退回部门次数（默认0）',
    dept_return_count       INT          NOT NULL DEFAULT 0 COMMENT '部门退回街道办次数（默认0，上限2次，第3次强制升级admin）',
    -- ---- 部门审核字段（需求 表21）----
    dept_audit_status       VARCHAR(20)  DEFAULT NULL COMMENT '部门审核状态：PENDING/APPROVED/REJECTED',
    dept_audit_time         DATETIME     DEFAULT NULL COMMENT '部门审核时间',
    dept_audit_user_id      BIGINT       DEFAULT NULL COMMENT '部门审核人ID',
    dept_audit_user_name    VARCHAR(50)  DEFAULT NULL COMMENT '部门审核人姓名',
    dept_audit_remark       VARCHAR(500) DEFAULT NULL COMMENT '部门审核意见/退回原因',
    dept_score              TINYINT      DEFAULT NULL COMMENT '部门评分（1-5星）',
    dept_score_time         DATETIME     DEFAULT NULL COMMENT '评分时间',
    -- ---- 终审与归档字段（需求 表23）----
    final_audit_status      VARCHAR(20)  DEFAULT NULL COMMENT '终审状态：PENDING/APPROVED/REJECTED',
    final_audit_time        DATETIME     DEFAULT NULL COMMENT '终审时间',
    final_audit_user_id     BIGINT       DEFAULT NULL COMMENT '终审人ID',
    final_audit_user_name   VARCHAR(50)  DEFAULT NULL COMMENT '终审人姓名',
    final_audit_remark      VARCHAR(500) DEFAULT NULL COMMENT '终审意见/退回原因',
    final_reject_target     VARCHAR(20)  DEFAULT NULL COMMENT '终审退回目标：dept/street',
    archive_time            DATETIME     DEFAULT NULL COMMENT '归档时间（办结后7天自动归档或手动归档）',
    archive_type            VARCHAR(20)  DEFAULT NULL COMMENT '归档类型：MANUAL/AUTO',
    is_completed            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '工单是否完成：1=已办结(CLOSED/EVALUATED) 0=未办结(驳回归档等)',
    is_archived             TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已归档（归档后只读，不可修改）',
    reopen_count            INT          NOT NULL DEFAULT 0 COMMENT '重开次数（默认0，上限1）',
    close_time              DATETIME     DEFAULT NULL COMMENT '办结时间',
    is_deleted              TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常/1已删除',
    create_time             DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    update_time             DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_status (status),
    KEY idx_category (category),
    KEY idx_category_county (category, county_code),
    KEY idx_region (region),
    KEY idx_level (level),
    KEY idx_event_type (event_type),
    KEY idx_create_time (create_time),
    KEY idx_is_overdue (is_overdue)
) ENGINE = InnoDB COMMENT = '管理端工单表（全量权威副本：建单即写入，含闭环处置/审核/终审/归档字段）';

-- 部门端工单副本表：与管理端工单表同构（含全部列与索引）
DROP TABLE IF EXISTS dept_work_order;
CREATE TABLE dept_work_order LIKE admin_work_order;
ALTER TABLE dept_work_order COMMENT = '部门端工单副本表（管理端分派后写入，department 清空/回退管理端时删除；id与管理端一致）';

-- 街道办端工单副本表：与管理端工单表同构（含全部列与索引）
DROP TABLE IF EXISTS street_work_order;
CREATE TABLE street_work_order LIKE admin_work_order;
ALTER TABLE street_work_order COMMENT = '街道办端工单副本表（部门分派街道办后写入，street_office 清空/重新分派时删除；id与管理端一致）';

-- 管理端终审归档表（归档数据独立存储）：归档动作（终审确认 FINAL/手动 MANUAL/满7天 AUTO）
-- 触发时整行快照自 admin_work_order（含全部列与索引）；重开审批通过时删除快照；
-- 同工单重复归档（重开后再归档）覆盖更新（一单一快照，最新为准）。
-- 终审归档页 / 归档数据管理 / 归档导出 只读本表；主表保留原行仅置 is_archived=1。
DROP TABLE IF EXISTS admin_order_archive;
CREATE TABLE admin_order_archive LIKE admin_work_order;
ALTER TABLE admin_order_archive COMMENT = '管理端终审归档表（归档时整行快照自 admin_work_order，重开时删除快照；主表保留原行仅置is_archived=1）';

-- ----------------------------------------------------------------------------
-- 11.1 工单-子诉求归并关联表（第二阶段去重归并，一个工单可归并多个同源子诉求）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS work_order_appeal;
CREATE TABLE work_order_appeal (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    order_id    BIGINT      NOT NULL COMMENT '工单ID',
    appeal_id   BIGINT      NOT NULL COMMENT '子诉求ID',
    is_primary  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否主诉求（建单首条，与admin_work_order.source_appeal_id一致）',
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '归并时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_appeal (appeal_id),
    KEY idx_order (order_id)
) ENGINE = InnoDB COMMENT = '工单-子诉求归并关联表（一个工单可归并多个同源子诉求）';

-- ----------------------------------------------------------------------------
-- 12. 工单流转日志表·三端拆分（需求 表6/表11 / API 11.1.5）
--     【状态机闭环】所有状态变更必须写入本表，禁止绕过
--     三端数据解耦：admin_order_log（全量）/ dept_order_log（工单已分派到部门后）
--     / street_order_log（工单已分派到街道办后）。三表内容一致（全量副本），各端时间线只读本端表。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS admin_order_log;
CREATE TABLE admin_order_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    order_id      BIGINT       NOT NULL COMMENT '工单ID',
    operate_type  VARCHAR(30)  NOT NULL COMMENT '操作类型：GENERATE/CONFIRM/UPGRADE/DISPATCH/RECEIVE/DISPOSE/RETURN/DELAY/AUDIT/FINAL_AUDIT/ARCHIVE/REOPEN 等',
    operator_id   BIGINT       DEFAULT NULL COMMENT '操作人ID',
    operator_role VARCHAR(20)  DEFAULT NULL COMMENT '操作角色：CITIZEN/SYSTEM/ADMIN/STREET/URBAN/PROPERTY/LIVELIHOOD（时间线角色化展示，不暴露姓名）',
    operate_content VARCHAR(1000) DEFAULT NULL COMMENT '操作内容',
    before_data   TEXT         DEFAULT NULL COMMENT '修改前数据（JSON）',
    after_data    TEXT         DEFAULT NULL COMMENT '修改后数据（JSON）',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT = '管理端工单流转日志表（操作类型：生成/确认/升级/分派/退回等；修改前后数据留痕）';

DROP TABLE IF EXISTS dept_order_log;
CREATE TABLE dept_order_log LIKE admin_order_log;
ALTER TABLE dept_order_log COMMENT = '部门端工单流转日志表（与管理端流转日志表同构；部门端时间线只读本表）';

DROP TABLE IF EXISTS street_order_log;
CREATE TABLE street_order_log LIKE admin_order_log;
ALTER TABLE street_order_log COMMENT = '街道办端工单流转日志表（与管理端流转日志表同构；街道办端时间线只读本表）';

-- ----------------------------------------------------------------------------
-- 13. 处置凭证附件表（需求 表20 / API 11.1.2）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS order_dispose_attachment;
CREATE TABLE order_dispose_attachment (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    order_id        BIGINT       NOT NULL COMMENT '工单ID',
    file_name       VARCHAR(200) NOT NULL COMMENT '文件原始名称',
    file_url        VARCHAR(500) NOT NULL COMMENT '文件存储路径',
    file_size       BIGINT       DEFAULT NULL COMMENT '文件大小（字节）',
    file_type       VARCHAR(50)  DEFAULT NULL COMMENT '文件类型',
    attachment_type VARCHAR(20)  NOT NULL COMMENT '附件类型：CREDENTIAL（凭证）/SCENE_PHOTO（现场照片）',
    upload_user_id  BIGINT       DEFAULT NULL COMMENT '上传人ID',
    upload_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE = InnoDB COMMENT = '处置凭证附件表（凭证/现场照片，最多5个，单个≤10MB）';

-- ----------------------------------------------------------------------------
-- 14. 延期申请表（需求 表22 / API 11.1.4/11.2.6）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS order_delay_apply;
CREATE TABLE order_delay_apply (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    order_id         BIGINT       NOT NULL COMMENT '工单ID',
    apply_user_id    BIGINT       NOT NULL COMMENT '申请人ID（街道办人员）',
    apply_user_name  VARCHAR(50)  DEFAULT NULL COMMENT '申请人姓名',
    delay_reason     VARCHAR(500) NOT NULL COMMENT '延期原因（必填）',
    expected_time    DATETIME     NOT NULL COMMENT '预计完成时间',
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '审批状态：PENDING/APPROVED/REJECTED',
    approve_user_id  BIGINT       DEFAULT NULL COMMENT '审批人ID（部门人员）',
    approve_time     DATETIME     DEFAULT NULL COMMENT '审批时间',
    approve_remark   VARCHAR(500) DEFAULT NULL COMMENT '审批备注',
    create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT = '延期申请表（最多2次，每次≤3个工作日）';

-- ----------------------------------------------------------------------------
-- 15. 满意度评价表（需求 表24 / API 11.4）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS order_evaluate;
CREATE TABLE order_evaluate (
    id                    BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    order_id              BIGINT      NOT NULL COMMENT '工单ID',
    appeal_no             VARCHAR(50) NOT NULL COMMENT '诉求编号',
    citizen_phone         VARCHAR(11) NOT NULL COMMENT '评价市民手机号',
    score                 TINYINT     NOT NULL COMMENT '评分（1-5）',
    label                 VARCHAR(30) NOT NULL COMMENT '评价标签：VERY_SATISFIED/SATISFIED/BASIC/DISSATISFIED/VERY_DISSATISFIED',
    remark                VARCHAR(200) DEFAULT NULL COMMENT '评价文字说明（≤200字）',
    is_reopen_applied     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否申请重开（默认0）',
    reopen_reason         VARCHAR(500) DEFAULT NULL COMMENT '重开申请原因（≤500字）',
    reopen_status         VARCHAR(20) DEFAULT NULL COMMENT '重开审批状态：PENDING/APPROVED/REJECTED',
    reopen_approve_time   DATETIME    DEFAULT NULL COMMENT '重开审批时间',
    reopen_approve_remark VARCHAR(500) DEFAULT NULL COMMENT '重开审批意见',
    evaluate_time         DATETIME    DEFAULT NULL COMMENT '评价时间（提交后不可修改）',
    create_time           DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_evaluate (order_id),
    KEY idx_appeal_no (appeal_no)
) ENGINE = InnoDB COMMENT = '满意度评价表（办结后7天内评价，超期未评价标记未评价）';

-- ----------------------------------------------------------------------------
-- 16. 分类体系表（需求 表7/表9 / API 7.x）
-- ----------------------------------------------------------------------------
-- 16.1 一级分类表（需求 10.3(1)：分类体系维护，一级分类/名称/分派部门）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS classify_primary;
CREATE TABLE classify_primary (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    code        VARCHAR(20) NOT NULL COMMENT '一级分类代码（URBAN/TRAFFIC/MARKET/...）',
    name        VARCHAR(50) NOT NULL COMMENT '一级分类名称（城市管理/交通管理/...）',
    department  VARCHAR(50) NOT NULL COMMENT '对应分派部门（城管部门/交通部门/...）',
    sort_order  INT         DEFAULT 0 COMMENT '排序',
    status      VARCHAR(20) NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB COMMENT = '一级分类表';

-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS classify_category;
CREATE TABLE classify_category (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    primary_category VARCHAR(20) NOT NULL COMMENT '一级分类代码（classify_primary.code）',
    sub_label       VARCHAR(50) DEFAULT NULL COMMENT '二级标签（如占道经营/违章搭建）',
    sort_order      INT         DEFAULT 0 COMMENT '排序',
    PRIMARY KEY (id),
    KEY idx_primary (primary_category)
) ENGINE = InnoDB COMMENT = '分类体系表（二级标签）';

-- ----------------------------------------------------------------------------
-- 17. 分类关键词表（需求 表7/表9 / API 7.1）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS classify_keyword;
CREATE TABLE classify_keyword (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    category_id BIGINT       NOT NULL COMMENT '分类ID（classify_category.id）',
    keyword     VARCHAR(100) NOT NULL COMMENT '关键词',
    weight      DECIMAL(6, 2) NOT NULL DEFAULT 1.00 COMMENT '权重（权重越高匹配优先级越高）',
    status      VARCHAR(20)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_keyword (category_id, keyword),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT = '分类关键词表';

-- ----------------------------------------------------------------------------
-- 18. 分类规则表（需求 表7/表9 / API 7.2）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS classify_rule;
CREATE TABLE classify_rule (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    category_id         BIGINT       NOT NULL COMMENT '分类ID（classify_category.id）',
    hit_count_threshold INT          NOT NULL DEFAULT 1 COMMENT '关键词命中数量阈值',
    weight_sum_threshold DECIMAL(8, 2) NOT NULL DEFAULT 1.00 COMMENT '权重求和阈值',
    UNIQUE KEY uk_category (category_id),
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT = '分类规则表（命中阈值即判定对应分类）';

-- ----------------------------------------------------------------------------
-- 19. RAG知识库样本表（需求 表7/表9 / API 7.3；按来源质量分级入库）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS rag_sample;
CREATE TABLE rag_sample (
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    category_id            BIGINT       NOT NULL COMMENT '分类ID（classify_category.id）',
    content                TEXT         NOT NULL COMMENT '样本内容',
    vector_id              VARCHAR(100) DEFAULT NULL COMMENT '向量ID（Redis Stack 向量库）',
    source                 VARCHAR(20)  NOT NULL COMMENT '样本来源：MANUAL_REVIEW/EXPERT/SATISFIED/CORRECTED',
    quality_level          VARCHAR(5)   NOT NULL COMMENT '质量等级：P0/P1/P2/P3',
    original_error_category VARCHAR(20) DEFAULT NULL COMMENT '原AI错误类别（人工修正样本标记，P3纠错样本）',
    status                 VARCHAR(20)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
    create_time            DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (id),
    KEY idx_category (category_id),
    KEY idx_source (source),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT = 'RAG知识库样本表（P0人工复核确认>P1专家标注>P2市民满意无退回>P3人工修正）';

-- ----------------------------------------------------------------------------
-- 20. 预警规则表（需求 表10 / API 8.1）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS warning_rule;
CREATE TABLE warning_rule (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    rule_name       VARCHAR(100) NOT NULL COMMENT '规则名称',
    time_dimension  VARCHAR(10)  NOT NULL COMMENT '时间维度：DAY/3_DAY/7_DAY',
    category        VARCHAR(20)  DEFAULT NULL COMMENT '一级分类（NULL=全部分类）',
    sub_category    VARCHAR(50)  DEFAULT NULL COMMENT '二级分类标签（NULL=该一级下全部分类）',
    region          VARCHAR(100) DEFAULT NULL COMMENT '辖区（NULL=全部辖区）',
    county_code     VARCHAR(20)  DEFAULT NULL COMMENT '区县编码（sys_region.region_code，level=COUNTY）',
    street_code     VARCHAR(20)  DEFAULT NULL COMMENT '街道/镇/乡编码',
    count_threshold INT          NOT NULL COMMENT '数量阈值（累计关注度）',
    warning_level   VARCHAR(20)  NOT NULL COMMENT '预警等级：GENERAL/SEVERE/URGENT',
    push_targets    VARCHAR(1000) DEFAULT NULL COMMENT '推送对象（旧格式兼容，逗号分隔）：ADMIN=管理端；纯分类code=全市该分类各部门（如URBAN）；区县:分类=指定区县部门（如130302:URBAN）。新数据请用 push_counties/push_categories 双限制',
    push_counties   VARCHAR(200) DEFAULT NULL COMMENT '辖区限制（逗号分隔区县code，空=全部区县）',
    push_categories VARCHAR(200) DEFAULT NULL COMMENT '部门类别限制（逗号分隔分类code，空=全部部门类别）',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED/DRAFT（草稿不参与扫描，人工确认后置 ENABLED）',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT = '预警规则表';

-- ----------------------------------------------------------------------------
-- 21. 预警记录表（需求 表10/表15 / API 8.2）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS warning_record;
CREATE TABLE warning_record (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    warning_no          VARCHAR(50)   NOT NULL COMMENT '预警编号（WR+日期+序号）',
    rule_id             BIGINT        DEFAULT NULL COMMENT '规则ID',
    warning_level       VARCHAR(20)   NOT NULL COMMENT '预警等级：GENERAL/SEVERE/URGENT',
    category            VARCHAR(20)   DEFAULT NULL COMMENT '一级分类',
    sub_category        VARCHAR(50)   DEFAULT NULL COMMENT '二级分类标签（NULL=无/全部分类）',
    region              VARCHAR(100)  DEFAULT NULL COMMENT '涉及辖区',
    county_code         VARCHAR(20)   DEFAULT NULL COMMENT '区县编码（sys_region.region_code，level=COUNTY）',
    county_name         VARCHAR(50)   DEFAULT NULL COMMENT '区县名称（街道办分发圈定区县用）',
    street_code         VARCHAR(20)   DEFAULT NULL COMMENT '街道/镇/乡编码',
    street_name         VARCHAR(50)   DEFAULT NULL COMMENT '街道/镇/乡名称',
    address_detail      VARCHAR(200)  DEFAULT NULL COMMENT '详细地址',
    is_broad            TINYINT(1)    DEFAULT NULL COMMENT '是否宽泛诉求：1宽泛（区县级）/0具体（NULL=历史数据未区分）',
    department          VARCHAR(1000) DEFAULT NULL COMMENT '归属部门（来自规则push_targets，逗号分隔；纯分类code=全市该分类各部门可见；区县:分类=仅该区县部门可见；NULL=仅推送ADMIN）',
    trigger_time        DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '触发时间',
    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '审核状态：PENDING待审核/SENT已发送/REJECTED已驳回（URGENT也走审核，列表置顶强提醒）',
    review_time         DATETIME      DEFAULT NULL COMMENT '管理端审核时间（发送/驳回时刻）',
    dept_sent_time      DATETIME      DEFAULT NULL COMMENT '预警发送给部门时间（审核发送时写入，部门端未读红点基准）',
    related_attention    INT           NOT NULL DEFAULT 0 COMMENT '累计关注度（窗口内命中工单 attention_count 求和）',
    ai_summary          TEXT          DEFAULT NULL COMMENT 'AI总结内容（AI生成·置信度X%标识）',
    measure             TEXT          DEFAULT NULL COMMENT '应对措施',
    ref_cases           VARCHAR(500)  DEFAULT NULL COMMENT '参考优秀处置案例编号（逗号分隔，预警建议RAG溯源展示）',
    respond_status      VARCHAR(20)   NOT NULL DEFAULT 'UNRESPONDED' COMMENT '响应状态：UNRESPONDED/RESPONDED',
    respond_time        DATETIME      DEFAULT NULL COMMENT '响应时间（自动记录响应时长）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_warning_no (warning_no),
    KEY idx_level (warning_level),
    KEY idx_status (status),
    KEY idx_department (department(191)),
    KEY idx_category_county (category, county_code),
    KEY idx_trigger_time (trigger_time),
    KEY idx_region (region)
) ENGINE = InnoDB COMMENT = '预警记录表（紧急预警未响应自动升级推送上级，全程留痕）';

-- ----------------------------------------------------------------------------
-- 21.1 预警街道办分发关系表（需求 11(5) 预警分发到街道办：部门分发→街道办按辖区查看并响应）
--      一份预警可分发给多个街道/乡镇；街道办端列表按本表归属过滤；
--      响应状态双向同步：街道办响应→主表全部响应后翻转；部门/管理端响应→本表全部置已响应
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS warning_street_dispatch;
CREATE TABLE warning_street_dispatch (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    warning_id        BIGINT       NOT NULL COMMENT '预警记录ID（warning_record.id）',
    street_code       VARCHAR(20)  DEFAULT NULL COMMENT '乡镇级区划编码（sys_region.region_code，无匹配时为空）',
    street_office     VARCHAR(100) NOT NULL COMMENT '街道/镇/乡名称（与街道办账号 region 同口径）',
    county_name       VARCHAR(50)  DEFAULT NULL COMMENT '所属区县名（冗余展示）',
    respond_status    VARCHAR(20)  NOT NULL DEFAULT 'UNRESPONDED' COMMENT '响应状态：UNRESPONDED未响应/RESPONDED已响应',
    dispatch_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '分发时间',
    respond_time      DATETIME     DEFAULT NULL COMMENT '响应时间（街道办标记或部门/管理端同步）',
    respond_user_id   BIGINT       DEFAULT NULL COMMENT '响应人ID（街道办账号）',
    respond_user_name VARCHAR(50)  DEFAULT NULL COMMENT '响应人姓名',
    PRIMARY KEY (id),
    KEY idx_warning_id (warning_id),
    KEY idx_street_office (street_office),
    KEY idx_respond_status (respond_status)
) ENGINE = InnoDB COMMENT = '预警街道办分发关系表（分发给未注册街道办亦可，注册后按其辖区自动可见）';

-- ----------------------------------------------------------------------------
-- 21.2 优秀处置案例库表（预警建议 RAG 检索来源，人工维护）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS warning_case;
CREATE TABLE warning_case (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    case_no         VARCHAR(50)  NOT NULL COMMENT '案例编号（WC+日期+序号）',
    title           VARCHAR(200) NOT NULL COMMENT '问题标题',
    category        VARCHAR(20)  DEFAULT NULL COMMENT '一级分类（URBAN/PROPERTY/LIVELIHOOD）',
    sub_category    VARCHAR(50)  DEFAULT NULL COMMENT '二级分类标签',
    region          VARCHAR(100) DEFAULT NULL COMMENT '所属辖区',
    county_code     VARCHAR(20)  DEFAULT NULL COMMENT '区县编码（sys_region.region_code，level=COUNTY）',
    county_name     VARCHAR(50)  DEFAULT NULL COMMENT '区县名称（检索前先按分类和辖区过滤用）',
    street_code     VARCHAR(20)  DEFAULT NULL COMMENT '街道/镇/乡编码',
    street_name     VARCHAR(50)  DEFAULT NULL COMMENT '街道/镇/乡名称',
    content         TEXT         NOT NULL COMMENT '诉求描述（检索匹配的问题侧文本）',
    measure_plan    TEXT         NOT NULL COMMENT '处置方案（优秀做法）',
    measure_result  TEXT         DEFAULT NULL COMMENT '处置效果/结果',
    source          VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT '来源：MANUAL=人工录入',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
    vector_id       VARCHAR(50)  DEFAULT NULL COMMENT '向量ID（case:v:<id>）',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_no (case_no),
    KEY idx_category (category),
    KEY idx_region (region),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT = '优秀处置案例库表（预警建议 RAG 检索来源，人工维护）';

-- ----------------------------------------------------------------------------
-- 22. 预判结果表（需求 表8 / API 8.3）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS prediction_result;
CREATE TABLE prediction_result (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    period          VARCHAR(10)   NOT NULL COMMENT '预判周期：DAY/3_DAY/7_DAY',
    category        VARCHAR(20)   NOT NULL COMMENT '诉求分类',
    sub_category    VARCHAR(50)   DEFAULT NULL COMMENT '二级分类标签（NULL=未区分到二级）',
    region          VARCHAR(100)  NOT NULL COMMENT '辖区',
    county_code     VARCHAR(20)   DEFAULT NULL COMMENT '区县编码（sys_region.region_code，level=COUNTY）',
    county_name     VARCHAR(50)   DEFAULT NULL COMMENT '区县名称（列表展示/筛选）',
    street_code     VARCHAR(20)   DEFAULT NULL COMMENT '街道/镇/乡编码',
    street_name     VARCHAR(50)   DEFAULT NULL COMMENT '街道/镇/乡名称',
    predicted_count INT           NOT NULL COMMENT '预测工单数',
    confidence      DECIMAL(5, 4) DEFAULT NULL COMMENT '置信度（0~1）',
    actual_count    INT           DEFAULT NULL COMMENT '实际工单数（周期结束后回填）',
    hit_status      VARCHAR(20)   DEFAULT NULL COMMENT '命中状态：HIT/MISS（评估后回填）',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '预判时间',
    PRIMARY KEY (id),
    KEY idx_period (period),
    KEY idx_category (category),
    KEY idx_sub_category (sub_category),
    KEY idx_region (region)
) ENGINE = InnoDB COMMENT = '预判结果表（预判效果评估：准确率/命中率/漏报率）';

-- ----------------------------------------------------------------------------
-- 23. 预判参数配置表（API 8.8）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS prediction_config;
CREATE TABLE prediction_config (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    predict_period       VARCHAR(10)  NOT NULL DEFAULT '7DAY' COMMENT '预测周期：DAY/3DAY/7DAY',
    confidence_threshold DECIMAL(5, 4) NOT NULL DEFAULT 0.70 COMMENT '置信度阈值（0~1）',
    cluster_count        INT          NOT NULL DEFAULT 10 COMMENT '热点聚类数量',
    history_range        VARCHAR(10)  NOT NULL DEFAULT '90' COMMENT '历史数据范围（30/90/180/365天）',
    update_time          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT = '预判参数配置表（单行配置，调整后即时生效）';

-- ----------------------------------------------------------------------------
-- 24. 敏感事件记录表（需求 表8 / API 10.7）
-- ----------------------------------------------------------------------------
-- 敏感事件记录表：市民提交诉求前置敏感检测命中后剥离入库（不生成父诉求/工单）
-- 快照字段内嵌完整诉求信息（appeal_id 为兼容旧记录的来源诉求ID，剥离后为 NULL）
DROP TABLE IF EXISTS sensitive_event;
CREATE TABLE sensitive_event (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    appeal_id      BIGINT      DEFAULT NULL COMMENT '来源诉求ID（旧版旁路模式）；剥离模式下为 NULL',
    appeal_no      VARCHAR(50) DEFAULT NULL COMMENT '敏感事件编号（SE+时间戳+序号；剥离模式生成）',
    risk_type      VARCHAR(20) NOT NULL COMMENT '风险类型：人身安全与公共安全/社会稳定与群体性事件/政治安全与意识形态/重大舆情风险/公职人员违纪违法/特殊人群与弱势群体权益',
    risk_level     VARCHAR(20) NOT NULL COMMENT '风险等级：GENERAL/SEVERE/URGENT',
    trigger_time   DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '触发时间',
    handle_status  VARCHAR(20) NOT NULL DEFAULT 'UNHANDLED' COMMENT '处理状态：UNHANDLED/HANDLED',
    push_targets   VARCHAR(500) DEFAULT NULL COMMENT '告警推送对象',
    advice         VARCHAR(1000) DEFAULT NULL COMMENT 'AI 处置建议',
    title          VARCHAR(200) DEFAULT NULL COMMENT '诉求标题（市民提交时为空）',
    content        TEXT        DEFAULT NULL COMMENT '诉求内容（原始全文快照）',
    category       VARCHAR(20) DEFAULT NULL COMMENT '诉求类别（8大类code或OTHER，市民提交自带）',
    citizen_name   VARCHAR(50) DEFAULT NULL COMMENT '市民姓名（提诉人）',
    citizen_phone  VARCHAR(11) DEFAULT NULL COMMENT '市民手机号（提诉人）',
    province       VARCHAR(50) DEFAULT NULL COMMENT '省级行政区划',
    city           VARCHAR(50) DEFAULT NULL COMMENT '市级行政区划',
    district       VARCHAR(50) DEFAULT NULL COMMENT '区级行政区划',
    street         VARCHAR(50) DEFAULT NULL COMMENT '街道（乡镇）',
    community      VARCHAR(50) DEFAULT NULL COMMENT '社区',
    address_detail VARCHAR(200) DEFAULT NULL COMMENT '详细地址补充',
    region         VARCHAR(500) DEFAULT NULL COMMENT '完整地址（district+street+address_detail拼接）',
    broad_flag     TINYINT(1)  DEFAULT NULL COMMENT '是否宽泛诉求：1 宽泛 / 0 具体',
    attachment_ids VARCHAR(200) DEFAULT NULL COMMENT '附件文件ID列表（逗号分隔，sys_file.id；剥离模式下附件不绑定诉求，靠此列关联）',
    PRIMARY KEY (id),
    KEY idx_risk_level (risk_level),
    KEY idx_appeal_no (appeal_no),
    KEY idx_trigger_time (trigger_time),
    KEY idx_citizen_phone (citizen_phone)
) ENGINE = InnoDB COMMENT = '敏感事件记录表（市民端前置检测剥离入库，内嵌诉求快照，不进入父诉求/拆分/建单流程）';

-- ----------------------------------------------------------------------------
-- 24.b 预判趋势表（需求 9(6) 趋势折线图 / 预判趋势查询）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS prediction_trend;
CREATE TABLE prediction_trend (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    period      VARCHAR(10)   NOT NULL COMMENT '预判周期：DAY/3_DAY/7_DAY',
    trend_date  VARCHAR(20)   DEFAULT NULL COMMENT '趋势日期（AI返回，yyyy-MM-dd）',
    predicted   INT           DEFAULT NULL COMMENT '当日预测工单数',
    confidence  DECIMAL(5,4)  DEFAULT NULL COMMENT '置信度（0~1）',
    create_time DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (id),
    KEY idx_period (period),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT = '预判趋势表（逐日趋势折线数据，仅插入与查询）';

-- ----------------------------------------------------------------------------
-- 25. 内部知识库表（需求 表16 / API 10.11）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS knowledge_base;
CREATE TABLE knowledge_base (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    knowledge_category VARCHAR(50) NOT NULL COMMENT '知识分类：处置规范/分类规则/政策法规/操作指引/FAQ',
    question_keywords VARCHAR(500) DEFAULT NULL COMMENT '问题关键词（逗号分隔）',
    standard_answer   TEXT        NOT NULL COMMENT '标准答复',
    source            VARCHAR(200) DEFAULT NULL COMMENT '来源',
    status            VARCHAR(20) NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
    create_time       DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_category (knowledge_category)
) ENGINE = InnoDB COMMENT = '内部知识库表（处置规范/分类规则/政策法规/操作指引/FAQ）';

-- ----------------------------------------------------------------------------
-- 26. 问答会话记录表（需求 表16 / API 8.7）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS chat_record;
CREATE TABLE chat_record (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    session_id       VARCHAR(50)  NOT NULL COMMENT '会话ID',
    user_id          BIGINT       DEFAULT NULL COMMENT '用户标识',
    role             VARCHAR(20)  NOT NULL COMMENT '角色：USER/ASSISTANT',
    question         TEXT         DEFAULT NULL COMMENT '问题',
    answer           TEXT         DEFAULT NULL COMMENT '答复（AI生成·置信度X%标识）',
    knowledge_sources VARCHAR(500) DEFAULT NULL COMMENT '知识来源',
    confidence       DECIMAL(5, 4) DEFAULT NULL COMMENT '置信度',
    satisfaction     TINYINT      DEFAULT NULL COMMENT '满意度',
    create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '时间',
    PRIMARY KEY (id),
    KEY idx_session (session_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB COMMENT = '问答会话记录表（内部人员问答）';

-- ----------------------------------------------------------------------------
-- 27/28. 站内消息表 + 通知偏好设置（API 4.6-4.8）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_message;
CREATE TABLE sys_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    phone       VARCHAR(11)  DEFAULT NULL COMMENT '接收市民手机号（市民消息）',
    user_id     BIGINT       DEFAULT NULL COMMENT '接收用户ID（工作人员消息）',
    msg_type    VARCHAR(50)  NOT NULL COMMENT '消息类型：ORDER_CREATED/ORDER_DISPATCHED/ORDER_RETURNED/ORDER_CLOSED/ORDER_REOPENED',
    title       VARCHAR(200) NOT NULL COMMENT '标题',
    content     VARCHAR(1000) NOT NULL COMMENT '内容（含变更原因与后续进展）',
    is_read     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已读：0未读/1已读',
    channel     VARCHAR(20)  NOT NULL DEFAULT 'IN_APP' COMMENT '通知渠道：IN_APP/SMS/APP_PUSH',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_phone (phone),
    KEY idx_user (user_id)
) ENGINE = InnoDB COMMENT = '站内消息表';

DROP TABLE IF EXISTS notification_settings;
CREATE TABLE notification_settings (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    phone       VARCHAR(11) NOT NULL COMMENT '市民手机号',
    sms_enabled TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否接收短信通知（进度通知默认站内，办结通知站内+短信）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB COMMENT = '通知偏好设置表';

-- ----------------------------------------------------------------------------
-- 29. 通用文件表（API 12.1 通用文件上传）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_file;
CREATE TABLE sys_file (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID（即 file_id）',
    biz_type        VARCHAR(50)  NOT NULL COMMENT '业务类型：appeal_attachment/dispose_proof/dispose_photo/avatar/archive_export',
    order_id        BIGINT       DEFAULT NULL COMMENT '关联工单ID',
    appeal_id       BIGINT       DEFAULT NULL COMMENT '关联父诉求ID（市民提交附件）',
    file_name       VARCHAR(200) NOT NULL COMMENT '原始文件名',
    file_url        VARCHAR(500) NOT NULL COMMENT '文件访问地址（相对路径）',
    file_size       BIGINT       NOT NULL COMMENT '文件大小（字节）',
    file_type       VARCHAR(100) DEFAULT NULL COMMENT 'MIME类型',
    upload_user_id  BIGINT       DEFAULT NULL COMMENT '上传用户ID',
    upload_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    KEY idx_biz_type (biz_type),
    KEY idx_order_id (order_id),
    KEY idx_appeal_id (appeal_id)
) ENGINE = InnoDB COMMENT = '通用文件表（一次上传、多处引用）';

-- ----------------------------------------------------------------------------
-- 30. 通知公告表·四端拆分（市民端首页"通知公告"模块 + 管理端/部门端/街道办发布）
--     四端数据解耦：admin_notice（管理端公告主表，全量权威，所有端发布的公告均落本表）
--     / dept_notice（部门端收件箱，DEPT 目标扇出，含部门级已读）
--     / street_notice（街道办端收件箱，STREET 目标扇出，含街道办级已读）
--     / citizen_notice（市民端公开栏，PUBLIC 目标扇出，市民只接收不发布）
--     发布权限矩阵（高权力→低权力）：ADMIN→部门/街道办/市民公开；DEPT→街道办/市民公开；STREET→市民公开
--     原 notice_target（接收对象）/ notice_read（逐条已读）两表已随拆分移除：
--     接收对象 = 三收件箱中存在的副本（可反查重构），已读 = 收件箱行上的 is_read
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS admin_notice;
CREATE TABLE admin_notice (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    title         VARCHAR(200) NOT NULL COMMENT '公告标题',
    content       TEXT COMMENT '公告正文（富文本/纯文本均可）',
    summary       VARCHAR(500) DEFAULT NULL COMMENT '公告摘要（列表卡片展示用）',
    publisher     VARCHAR(50)  DEFAULT NULL COMMENT '发布人姓名（脱敏）',
    publisher_type VARCHAR(20) NOT NULL DEFAULT 'ADMIN' COMMENT '发布者类型：ADMIN管理员/DEPT部门/STREET街道办',
    publisher_dept VARCHAR(20)  DEFAULT NULL COMMENT '发布部门（DEPT时填，区县:分类复合键如130302:URBAN，与sys_user.user_type+region_code同源；STREET时为NULL）',
    top_flag      TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否置顶：0 普通 / 1 置顶',
    status        TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '状态：0 已下架 / 1 展示中',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（也是发布时间）',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_status_top_time (status, top_flag, create_time)
) ENGINE = InnoDB COMMENT = '管理端公告主表（通知公告四端拆分：所有端发布的公告均落本表，全量权威）';

DROP TABLE IF EXISTS dept_notice;
CREATE TABLE dept_notice (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    notice_id      BIGINT       NOT NULL COMMENT '源公告ID（admin_notice.id，发布方编辑/下架/重发联动键）',
    title          VARCHAR(200) NOT NULL COMMENT '公告标题（快照，发布方编辑时同步）',
    content        TEXT COMMENT '公告正文（快照）',
    summary        VARCHAR(500) DEFAULT NULL COMMENT '公告摘要（快照）',
    publisher      VARCHAR(50)  DEFAULT NULL COMMENT '发布人姓名（脱敏）',
    publisher_type VARCHAR(20)  NOT NULL DEFAULT 'ADMIN' COMMENT '发布者类型：ADMIN/DEPT/STREET',
    publisher_dept VARCHAR(20)  DEFAULT NULL COMMENT '发布部门（DEPT时填，区县:分类复合键如130302:URBAN）',
    top_flag       TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否置顶：0 普通 / 1 置顶',
    status         TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '状态：0 已下架 / 1 展示中（发布方下架时同步）',
    dept_code      VARCHAR(20)  NOT NULL COMMENT '接收部门（区县:分类复合键如130302:URBAN；历史数据为纯分类code如URBAN=全市该分类）',
    is_read        TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '部门级已读：0 未读 / 1 已读（任一部门用户点开详情即置1）',
    read_time      DATETIME     DEFAULT NULL COMMENT '首次已读时间',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '到达时间（扇出时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_notice_dept (notice_id, dept_code),
    KEY idx_dept (dept_code, status, is_read)
) ENGINE = InnoDB COMMENT = '部门端通知收件箱（DEPT目标扇出的部门级快照，含部门级已读）';

DROP TABLE IF EXISTS street_notice;
CREATE TABLE street_notice (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    notice_id      BIGINT       NOT NULL COMMENT '源公告ID（admin_notice.id）',
    title          VARCHAR(200) NOT NULL COMMENT '公告标题（快照）',
    content        TEXT COMMENT '公告正文（快照）',
    summary        VARCHAR(500) DEFAULT NULL COMMENT '公告摘要（快照）',
    publisher      VARCHAR(50)  DEFAULT NULL COMMENT '发布人姓名（脱敏）',
    publisher_type VARCHAR(20)  NOT NULL DEFAULT 'ADMIN' COMMENT '发布者类型：ADMIN/DEPT/STREET',
    publisher_dept VARCHAR(20)  DEFAULT NULL COMMENT '发布部门（DEPT时填，区县:分类复合键如130302:URBAN）',
    top_flag       TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否置顶：0 普通 / 1 置顶',
    status         TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '状态：0 已下架 / 1 展示中（发布方下架时同步）',
    street_name    VARCHAR(50)  NOT NULL COMMENT '接收街道办名（sys_region 街道级名称）',
    is_read        TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '街道办级已读：0 未读 / 1 已读',
    read_time      DATETIME     DEFAULT NULL COMMENT '首次已读时间',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '到达时间（扇出时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_notice_street (notice_id, street_name),
    KEY idx_street (street_name, status, is_read)
) ENGINE = InnoDB COMMENT = '街道办端通知收件箱（STREET目标扇出的街道办级快照，含街道办级已读）';

DROP TABLE IF EXISTS citizen_notice;
CREATE TABLE citizen_notice (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    notice_id      BIGINT       NOT NULL COMMENT '源公告ID（admin_notice.id）',
    title          VARCHAR(200) NOT NULL COMMENT '公告标题（快照）',
    content        TEXT COMMENT '公告正文（快照）',
    summary        VARCHAR(500) DEFAULT NULL COMMENT '公告摘要（快照）',
    publisher      VARCHAR(50)  DEFAULT NULL COMMENT '发布人姓名（脱敏）',
    publisher_type VARCHAR(20)  NOT NULL DEFAULT 'ADMIN' COMMENT '发布者类型：ADMIN/DEPT/STREET',
    publisher_dept VARCHAR(20)  DEFAULT NULL COMMENT '发布部门（DEPT时填，区县:分类复合键如130302:URBAN）',
    top_flag       TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否置顶：0 普通 / 1 置顶',
    status         TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '状态：0 已下架 / 1 展示中（发布方下架时同步）',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '到达时间（扇出时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_notice (notice_id)
) ENGINE = InnoDB COMMENT = '市民端公开通知（PUBLIC目标扇出的市民端副本，市民只接收不发布，无已读跟踪）';

-- ----------------------------------------------------------------------------
-- 演示数据：通知公告（admin_notice + 扇出 citizen_notice 公开副本）
-- ----------------------------------------------------------------------------
INSERT INTO admin_notice (title, content, summary, publisher, publisher_type, publisher_dept, top_flag, status) VALUES
('关于 12345 热线系统升级维护的通知',
 '尊敬的用户：\n\n为进一步提升服务体验，我们计划于2026年9月5日凌晨00:00-06:00进行系统升级维护。届时热线电话及在线服务可能短暂中断，给您带来的不便敬请谅解。\n\n如有紧急事项，请拨打备用热线：010-12345678。\n\n12345 政务热线服务中心\n2026年9月1日',
 '系统升级维护期间服务暂时中断，请提前安排好相关事务', '系统管理员', 'ADMIN', NULL, 1, 1),

('关于优化诉求受理流程的公告',
 '为进一步提升诉求处理效率，我们对诉求受理流程进行了优化：\n1. 新增智能分类功能，系统将自动识别诉求类型并快速分派至相关部门；\n2. 压缩处理时限，复杂诉求处理时限由15个工作日压缩至10个工作日；\n3. 新增短信进度通知服务，办结后第一时间短信告知。\n\n欢迎广大用户提出宝贵意见！',
 '新增智能分类、压缩处理时限、短信进度通知', '运营中心', 'ADMIN', NULL, 0, 1),

('「民生服务进社区」活动的通知',
 '为更好地服务群众，我中心将联合街道办开展「民生服务进社区」活动，届时将有工作人员现场受理群众诉求，并提供政策咨询服务。\n\n活动地点：朝阳区劲松街道综合服务中心\n活动时间：2026年9月15日（周六）9:00-17:00\n\n欢迎广大居民积极参与！',
 '9月15日走进朝阳区劲松街道，现场受理诉求', '外联部', 'ADMIN', NULL, 0, 1);

-- 存量公告默认视为管理员发布的公开通知：扇出市民端公开副本（市民端全员可见）
INSERT INTO citizen_notice (notice_id, title, content, summary, publisher, publisher_type, publisher_dept, top_flag, status, create_time)
SELECT id, title, content, summary, publisher, publisher_type, publisher_dept, top_flag, status, create_time
FROM admin_notice;

-- ----------------------------------------------------------------------------
-- 31. 区划字典表（区县 + 街道 + 镇 + 乡；系统仅针对秦皇岛市，无省/市级）
--     level：COUNTY 区县 / STREET 街道 / TOWN 镇 / TOWNSHIP 乡
--     parent_code：乡镇级指向所属区县的 region_code；区县为 NULL（顶层）
--     编码规则：区县 6 位国标码；乡镇级 = 区县码 + 3 位序号（街道 0xx/镇 1xx/乡 2xx）
--     测试数据见 27-seed-region.sql
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_region;
CREATE TABLE sys_region (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    region_code  VARCHAR(20)  NOT NULL COMMENT '区划编码（区县6位/乡镇级9位，唯一）',
    region_name  VARCHAR(50)  NOT NULL COMMENT '区划名称（如 海港区 / 海滨路街道 / 东港镇 / 渤海乡）',
    level        VARCHAR(10)  NOT NULL COMMENT '层级：COUNTY区县/STREET街道/TOWN镇/TOWNSHIP乡',
    parent_code  VARCHAR(20)  DEFAULT NULL COMMENT '上级区划编码（乡镇级指向区县，区县为NULL）',
    sort_order   INT          NOT NULL DEFAULT 0 COMMENT '排序号',
    status       VARCHAR(10)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_region_code (region_code),
    KEY idx_parent_code (parent_code),
    KEY idx_level (level)
) ENGINE = InnoDB COMMENT = '区划字典表（区县/街道/镇/乡自关联，秦皇岛市固定，无省市级）';

-- ----------------------------------------------------------------------------
-- 32. 模块未读游标表（侧边菜单未读红点：管理端/部门端/街道办端）
--     未读数 = 各模块在权限范围内、时间晚于该用户该模块「最近查看游标」的新增记录数
--     首次访问无游标时该模块未读为 0（只算上线后新增），进入模块页面时上报游标清零
--     预警与预判拆为 warning_record / sensitive_event 两个独立游标，各自 tab 分别清零
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_module_read;
CREATE TABLE sys_module_read (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id        BIGINT      NOT NULL COMMENT '用户ID（sys_user.id，三端账号）',
    module         VARCHAR(50) NOT NULL COMMENT '模块标识：appeal_parent/appeal_child/work_order/warning_record/sensitive_event/audit_sys/audit_dept/audit_street/account_audit',
    last_viewed_at DATETIME    NOT NULL COMMENT '该模块最近一次查看时间（未读游标）',
    create_time    DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_module (user_id, module)
) ENGINE = InnoDB COMMENT = '模块未读游标表（用户×模块最近查看时间，用于侧边菜单未读红点）';
