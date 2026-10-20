SET NAMES utf8mb4;
-- ============================================================================
-- 12345政务热线 — 账号与权限种子数据
-- 执行前提：先执行 00-init-all.sql 建库建表
-- 内容：四端账号（管理端/部门端/街道办端/市民端说明）+ 角色/菜单/关联
-- 其他种子数据见：25-seed-classify.sql（分类体系）、26-seed-config.sql（知识库/预判配置）
-- ============================================================================

USE hotline_12345;

-- ==================== 零、清理旧种子数据（幂等，防重复插入/唯一键冲突） ====================
-- 删除顺序：先删关联表（user_role/role_menu）再删主表（user/role/menu），防悬空引用
-- 账号按工号精准删除：不影响通过注册/管理端页面新增的真实账号
DELETE FROM sys_user_role;   -- 用户-角色关联（随角色全量重建，全删防悬空）
DELETE FROM sys_role_menu;   -- 角色-菜单关联（随角色/菜单全量重建）
DELETE FROM sys_user WHERE job_no IN
      ('admin001','urban001','traffic001','market001','environment001',
       'housing001','labor001','public001','health001',
       'street001','street002','street003','street004','street005','street006','street007',
       -- 56 部门账号（7 区县 × 8 分类，region_code 为区县级）
       'hgurban001','hgtraffic001','hgmarket001','hgenvironment001','hghousing001','hglabor001','hgpublic001','hghealth001',
       'shgurban001','shgtraffic001','shgmarket001','shgenvironment001','shghousing001','shglabor001','shgpublic001','shghealth001',
       'bdhurban001','bdhtraffic001','bdhmarket001','bdhenvironment001','bdhhousing001','bdhlabor001','bdhpublic001','bdhhealth001',
       'fnurban001','fntraffic001','fnmarket001','fnenvironment001','fnhousing001','fnlabor001','fnpublic001','fnhealth001',
       'qlurban001','qltraffic001','qlmarket001','qlenvironment001','qlhousing001','qllabor001','qlpublic001','qlhealth001',
       'clurban001','cltraffic001','clmarket001','clenvironment001','clhousing001','cllabor001','clpublic001','clhealth001',
       'llurban001','lltraffic001','llmarket001','llenvironment001','llhousing001','lllabor001','llpublic001','llhealth001');
DELETE FROM sys_role;        -- 角色为固定种子集合（7 条），全量重建
DELETE FROM sys_menu;        -- 菜单为代码级固定集合（15 项），全量重建
DELETE FROM citizen_account WHERE phone = '15947339264';   -- 测试市民账号（按手机号精准删）

-- ==================== 一、管理端账号 ====================
-- 系统管理员（API 5.1 示例账号，密码 Admin@12345 的 bcrypt 哈希）
INSERT INTO sys_user (job_no, name, phone, password, company, user_type, region, status, audit_status)
VALUES ('admin001', '系统管理员', NULL,
        '$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm',
        '12345政务服务热线中心', 'ADMIN', '全市', 'ENABLED', 'APPROVED');

-- ==================== 二、部门端账号（56 = 7 区县 × 8 分类） ====================
-- 密码统一 Admin@12345（复用 admin 哈希）；user_type=8大分类 code 不变；
-- region=区县+部门拼接名；region_code=区县级 sys_region.region_code（56部门口径）
INSERT INTO sys_user (job_no, name, phone, password, company, user_type, region, region_code, status, audit_status) VALUES
-- 海港区(130302)
('hgurban001','海港区城管部门测试员','13800004001','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','海港区城管部门','URBAN','海港区城管部门','130302','ENABLED','APPROVED'),
('hgtraffic001','海港区交通部门测试员','13800004002','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','海港区交通部门','TRAFFIC','海港区交通部门','130302','ENABLED','APPROVED'),
('hgmarket001','海港区市场监管部门测试员','13800004003','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','海港区市场监管部门','MARKET','海港区市场监管部门','130302','ENABLED','APPROVED'),
('hgenvironment001','海港区生态环境部门测试员','13800004004','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','海港区生态环境部门','ENVIRONMENT','海港区生态环境部门','130302','ENABLED','APPROVED'),
('hghousing001','海港区住建部门测试员','13800004005','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','海港区住建部门','HOUSING','海港区住建部门','130302','ENABLED','APPROVED'),
('hglabor001','海港区人社部门测试员','13800004006','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','海港区人社部门','LABOR','海港区人社部门','130302','ENABLED','APPROVED'),
('hgpublic001','海港区市政部门测试员','13800004007','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','海港区市政部门','PUBLIC','海港区市政部门','130302','ENABLED','APPROVED'),
('hghealth001','海港区卫健部门测试员','13800004008','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','海港区卫健部门','HEALTH','海港区卫健部门','130302','ENABLED','APPROVED'),
-- 山海关区(130303)
('shgurban001','山海关区城管部门测试员','13800004009','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','山海关区城管部门','URBAN','山海关区城管部门','130303','ENABLED','APPROVED'),
('shgtraffic001','山海关区交通部门测试员','13800004010','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','山海关区交通部门','TRAFFIC','山海关区交通部门','130303','ENABLED','APPROVED'),
('shgmarket001','山海关区市场监管部门测试员','13800004011','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','山海关区市场监管部门','MARKET','山海关区市场监管部门','130303','ENABLED','APPROVED'),
('shgenvironment001','山海关区生态环境部门测试员','13800004012','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','山海关区生态环境部门','ENVIRONMENT','山海关区生态环境部门','130303','ENABLED','APPROVED'),
('shghousing001','山海关区住建部门测试员','13800004013','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','山海关区住建部门','HOUSING','山海关区住建部门','130303','ENABLED','APPROVED'),
('shglabor001','山海关区人社部门测试员','13800004014','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','山海关区人社部门','LABOR','山海关区人社部门','130303','ENABLED','APPROVED'),
('shgpublic001','山海关区市政部门测试员','13800004015','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','山海关区市政部门','PUBLIC','山海关区市政部门','130303','ENABLED','APPROVED'),
('shghealth001','山海关区卫健部门测试员','13800004016','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','山海关区卫健部门','HEALTH','山海关区卫健部门','130303','ENABLED','APPROVED'),
-- 北戴河区(130304)
('bdhurban001','北戴河区城管部门测试员','13800004017','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','北戴河区城管部门','URBAN','北戴河区城管部门','130304','ENABLED','APPROVED'),
('bdhtraffic001','北戴河区交通部门测试员','13800004018','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','北戴河区交通部门','TRAFFIC','北戴河区交通部门','130304','ENABLED','APPROVED'),
('bdhmarket001','北戴河区市场监管部门测试员','13800004019','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','北戴河区市场监管部门','MARKET','北戴河区市场监管部门','130304','ENABLED','APPROVED'),
('bdhenvironment001','北戴河区生态环境部门测试员','13800004020','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','北戴河区生态环境部门','ENVIRONMENT','北戴河区生态环境部门','130304','ENABLED','APPROVED'),
('bdhhousing001','北戴河区住建部门测试员','13800004021','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','北戴河区住建部门','HOUSING','北戴河区住建部门','130304','ENABLED','APPROVED'),
('bdhlabor001','北戴河区人社部门测试员','13800004022','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','北戴河区人社部门','LABOR','北戴河区人社部门','130304','ENABLED','APPROVED'),
('bdhpublic001','北戴河区市政部门测试员','13800004023','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','北戴河区市政部门','PUBLIC','北戴河区市政部门','130304','ENABLED','APPROVED'),
('bdhhealth001','北戴河区卫健部门测试员','13800004024','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','北戴河区卫健部门','HEALTH','北戴河区卫健部门','130304','ENABLED','APPROVED'),
-- 抚宁区(130306)
('fnurban001','抚宁区城管部门测试员','13800004025','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','抚宁区城管部门','URBAN','抚宁区城管部门','130306','ENABLED','APPROVED'),
('fntraffic001','抚宁区交通部门测试员','13800004026','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','抚宁区交通部门','TRAFFIC','抚宁区交通部门','130306','ENABLED','APPROVED'),
('fnmarket001','抚宁区市场监管部门测试员','13800004027','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','抚宁区市场监管部门','MARKET','抚宁区市场监管部门','130306','ENABLED','APPROVED'),
('fnenvironment001','抚宁区生态环境部门测试员','13800004028','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','抚宁区生态环境部门','ENVIRONMENT','抚宁区生态环境部门','130306','ENABLED','APPROVED'),
('fnhousing001','抚宁区住建部门测试员','13800004029','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','抚宁区住建部门','HOUSING','抚宁区住建部门','130306','ENABLED','APPROVED'),
('fnlabor001','抚宁区人社部门测试员','13800004030','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','抚宁区人社部门','LABOR','抚宁区人社部门','130306','ENABLED','APPROVED'),
('fnpublic001','抚宁区市政部门测试员','13800004031','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','抚宁区市政部门','PUBLIC','抚宁区市政部门','130306','ENABLED','APPROVED'),
('fnhealth001','抚宁区卫健部门测试员','13800004032','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','抚宁区卫健部门','HEALTH','抚宁区卫健部门','130306','ENABLED','APPROVED'),
-- 青龙满族自治县(130321)
('qlurban001','青龙满族自治县城管部门测试员','13800004033','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','青龙满族自治县城管部门','URBAN','青龙满族自治县城管部门','130321','ENABLED','APPROVED'),
('qltraffic001','青龙满族自治县交通部门测试员','13800004034','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','青龙满族自治县交通部门','TRAFFIC','青龙满族自治县交通部门','130321','ENABLED','APPROVED'),
('qlmarket001','青龙满族自治县市场监管部门测试员','13800004035','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','青龙满族自治县市场监管部门','MARKET','青龙满族自治县市场监管部门','130321','ENABLED','APPROVED'),
('qlenvironment001','青龙满族自治县生态环境部门测试员','13800004036','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','青龙满族自治县生态环境部门','ENVIRONMENT','青龙满族自治县生态环境部门','130321','ENABLED','APPROVED'),
('qlhousing001','青龙满族自治县住建部门测试员','13800004037','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','青龙满族自治县住建部门','HOUSING','青龙满族自治县住建部门','130321','ENABLED','APPROVED'),
('qllabor001','青龙满族自治县人社部门测试员','13800004038','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','青龙满族自治县人社部门','LABOR','青龙满族自治县人社部门','130321','ENABLED','APPROVED'),
('qlpublic001','青龙满族自治县市政部门测试员','13800004039','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','青龙满族自治县市政部门','PUBLIC','青龙满族自治县市政部门','130321','ENABLED','APPROVED'),
('qlhealth001','青龙满族自治县卫健部门测试员','13800004040','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','青龙满族自治县卫健部门','HEALTH','青龙满族自治县卫健部门','130321','ENABLED','APPROVED'),
-- 昌黎县(130322)
('clurban001','昌黎县城管部门测试员','13800004041','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','昌黎县城管部门','URBAN','昌黎县城管部门','130322','ENABLED','APPROVED'),
('cltraffic001','昌黎县交通部门测试员','13800004042','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','昌黎县交通部门','TRAFFIC','昌黎县交通部门','130322','ENABLED','APPROVED'),
('clmarket001','昌黎县市场监管部门测试员','13800004043','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','昌黎县市场监管部门','MARKET','昌黎县市场监管部门','130322','ENABLED','APPROVED'),
('clenvironment001','昌黎县生态环境部门测试员','13800004044','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','昌黎县生态环境部门','ENVIRONMENT','昌黎县生态环境部门','130322','ENABLED','APPROVED'),
('clhousing001','昌黎县住建部门测试员','13800004045','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','昌黎县住建部门','HOUSING','昌黎县住建部门','130322','ENABLED','APPROVED'),
('cllabor001','昌黎县人社部门测试员','13800004046','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','昌黎县人社部门','LABOR','昌黎县人社部门','130322','ENABLED','APPROVED'),
('clpublic001','昌黎县市政部门测试员','13800004047','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','昌黎县市政部门','PUBLIC','昌黎县市政部门','130322','ENABLED','APPROVED'),
('clhealth001','昌黎县卫健部门测试员','13800004048','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','昌黎县卫健部门','HEALTH','昌黎县卫健部门','130322','ENABLED','APPROVED'),
-- 卢龙县(130324)
('llurban001','卢龙县城管部门测试员','13800004049','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','卢龙县城管部门','URBAN','卢龙县城管部门','130324','ENABLED','APPROVED'),
('lltraffic001','卢龙县交通部门测试员','13800004050','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','卢龙县交通部门','TRAFFIC','卢龙县交通部门','130324','ENABLED','APPROVED'),
('llmarket001','卢龙县市场监管部门测试员','13800004051','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','卢龙县市场监管部门','MARKET','卢龙县市场监管部门','130324','ENABLED','APPROVED'),
('llenvironment001','卢龙县生态环境部门测试员','13800004052','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','卢龙县生态环境部门','ENVIRONMENT','卢龙县生态环境部门','130324','ENABLED','APPROVED'),
('llhousing001','卢龙县住建部门测试员','13800004053','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','卢龙县住建部门','HOUSING','卢龙县住建部门','130324','ENABLED','APPROVED'),
('lllabor001','卢龙县人社部门测试员','13800004054','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','卢龙县人社部门','LABOR','卢龙县人社部门','130324','ENABLED','APPROVED'),
('llpublic001','卢龙县市政部门测试员','13800004055','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','卢龙县市政部门','PUBLIC','卢龙县市政部门','130324','ENABLED','APPROVED'),
('llhealth001','卢龙县卫健部门测试员','13800004056','$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm','卢龙县卫健部门','HEALTH','卢龙县卫健部门','130324','ENABLED','APPROVED');

-- ==================== 三、街道办端账号（每区县一个，对应 27-seed-region.sql 的 7 个区县） ====================
-- region=区县名：权限范围为本辖区工单（后端按 region 前缀匹配）；
-- street001 海港区同时匹配 23-test-appeals.sql 测试诉求的辖区
INSERT INTO sys_user (job_no, name, phone, password, company, user_type, region, status, audit_status) VALUES
('street001', '海港区街道办测试员',       '13800003009', '$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm', '街道办', 'STREET', '东环路街道',         'ENABLED', 'APPROVED'),
('street002', '山海关区街道办测试员',     '13800003010', '$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm', '街道办', 'STREET', '东街街道',       'ENABLED', 'APPROVED'),
('street003', '北戴河区街道办测试员',     '13800003011', '$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm', '街道办', 'STREET', '东山街道',       'ENABLED', 'APPROVED'),
('street004', '抚宁区街道办测试员',       '13800003012', '$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm', '街道办', 'STREET', '骊城街道',         'ENABLED', 'APPROVED'),
('street005', '青龙满族自治县街道办测试员','13800003013', '$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm', '街道办', 'STREET', '都阳路街道', 'ENABLED', 'APPROVED'),
('street006', '昌黎县街道办测试员',       '13800003014', '$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm', '街道办', 'STREET', '两山乡',         'ENABLED', 'APPROVED'),
('street007', '卢龙县街道办测试员',       '13800003015', '$2b$10$rrcxUEv1ntfKsbT7M5WB1e0yclRtgd/AeEvY76Jwwj.VakJH1Tlkm', '街道办', 'STREET', '下寨乡',         'ENABLED', 'APPROVED');

-- ==================== 四、市民端（无需预置账号） ====================
-- 市民登录为手机号 + 短信验证码（API 3.3 citizenLogin），不落 sys_user 表、无需注册；
-- 测试市民的手机号直接使用 23-test-appeals.sql 诉求数据中的 citizen_phone（13800002001~ 等）

-- ==================== 五、角色与菜单权限（账号体系配套） ====================
-- 角色（需求 表3：系统管理员/城管/物业/民生部门人员/街道办人员/审核专员/督办专员）
INSERT INTO sys_role (code, name, scope, description) VALUES
('ADMIN',            '系统管理员',  '全市',     '系统全部功能与数据权限'),
('URBAN_STAFF',      '城管部门人员', '城管分类全辖区', '城管类工单核验、分派、审核'),
('PROPERTY_STAFF',   '物业部门人员', '物业分类全辖区', '物业类工单核验、分派、审核'),
('LIVELIHOOD_STAFF', '民生部门人员', '民生分类全辖区', '民生类工单核验、分派、审核'),
('STREET_STAFF',     '街道办人员',  '本辖区',   '本辖区工单接收、处置、提交'),
('AUDITOR',          '审核专员',    '待定',     '工单审核复核'),
('SUPERVISOR',       '督办专员',    '待定',     '超期督办与预警处置');

-- 菜单权限（P0 范围）
INSERT INTO sys_menu (code, name, parent_id, route, perm, sort_order) VALUES
('dashboard',    '系统总览',   0, '/dashboard',    'dashboard:view',    1),
('user-manage',  '用户账号管理', 0, '/users',        'user:manage',       2),
('account-audit','账号审核',   0, '/audits',       'audit:manage',      3),
('appeal-manage','诉求管理',   0, '/appeals',      'appeal:view',       4),
('order-manage', '工单管理',   0, '/orders',       'order:manage',      5),
('classify',     '分类规则维护', 0, '/classify',     'classify:manage',   6),
('predict',      '诉求预判',   0, '/predictions',  'predict:view',      7),
('warning',      '预警管理',   0, '/warnings',      'warning:manage',    8),
('stats',        '全局数据统计', 0, '/stats',        'stats:view',        9),
('final-audit',  '终审归档',   0, '/final-audit',  'final:audit',       10),
('logs',         '系统审计',   0, '/logs',         'log:view',          11),
('staff-orders', '权限内工单', 0, '/staff/orders', 'staff:order:view',  12),
('staff-warnings','局部预警',  0, '/staff/warnings','staff:warning:view',13),
('staff-audit',  '工单审核',   0, '/staff/auditing','staff:audit',      14),
('profile',      '个人中心',   0, '/profile',      'profile:view',      15);

-- 管理员角色拥有全部菜单
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT (SELECT id FROM sys_role WHERE code = 'ADMIN'), id FROM sys_menu;

-- 管理员账号绑定 ADMIN 角色（部门/街道办账号按 user_type 鉴权，暂不绑角色）
INSERT INTO sys_user_role (user_id, role_id)
SELECT (SELECT id FROM sys_user WHERE job_no = 'admin001'), id FROM sys_role WHERE code = 'ADMIN';

-- ============================================================================
-- 账号一览（密码统一 Admin@12345）：
--   管理端  admin001            全部功能
--   部门端  56 个账号           7 区县 × 8 分类（海港区/山海关区/北戴河区/抚宁区/
--          青龙满族自治县/昌黎县/卢龙县 × 城管/交通/市场监管/生态环境/住建/人社/市政/卫健）
--          工号规则：区县前缀(hg/shg/bdh/fn/ql/cl/ll)+分类code+001，如 hgurban001=海港区城管
--   街道办 street001~007         七区县各一个（海港区/山海关区/北戴河区/抚宁区/
--          青龙满族自治县/昌黎县/卢龙县），本辖区工单接收/处置/提交
--   市民端 无需账号             手机号+短信验证码登录（注册市民见 citizen_account）
-- ============================================================================

-- ==================== 五、市民端测试账号（citizen_account） ====================
-- 白先生：密码 123456 的 bcrypt 哈希（与上不同，演示用弱密码）；辖区/街道取自 sys_region
INSERT INTO citizen_account (phone, password, name, email, district_code, district, street_code, street, address_detail)
VALUES ('15947339264', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '白先生',
        '3056380589@qq.com', '130302', '海港区', '130302001', '文化路街道', '文化路 123 号');
