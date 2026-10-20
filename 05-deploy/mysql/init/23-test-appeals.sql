SET NAMES utf8mb4;
-- ============================================================================
-- 诉求拆分+归并测试数据（配合「拆分所选」「生成工单」验证两大核心功能）
--
-- ★ 预期总数：20 条父诉求（全部 TITLE_PENDING，位置均为秦皇岛海港区精确到街道/社区）
--   → 拆分后 28 条子诉求（多事件组 14 + 同源组 14 条各拆 1 条）
--   → 生成 18 张工单（多事件组 14 + 同源组归并 4）
--   → 本文件每次执行先清理诉求域与工单域，再重新插入父诉求（幂等重置）
--
-- 功能一：按事件数量拆分子诉求（AP001~006，共拆 14 条）
--   每条父诉求包含 2~3 个跨类别独立事件，AI 拆分后每事件一条子诉求
--   14 条子诉求事件互不相同 → 各自独立建单，共 14 张工单
--
-- 功能二：同源工单归并与关注度累加（AP011~021、AP023~025，各拆 1 条共 14 条，归并 4 张）
--   组1（AP011~015 占道经营，5条）→ 1 张 URBAN 工单，关注度 5 → 一级（≥5 恰好达标）
--   组2（AP016~018 停水，3条）    → 1 张 PUBLIC 工单，关注度 3 → 二级（3-4）
--   组3（AP019~021 工地欠薪，3条）→ 1 张 LABOR 工单，关注度 3 → 二级（3-4）
--   组4（AP023~025 电梯故障，3条）→ 1 张 HOUSING 工单，关注度 3 → 二级（3-4）
--
-- 功能三：文本清洗功能验证（AP001~AP004，对应 preprocess.py 三段管线：
--   clean_text 清洗 → normalize_text 归一化 → filter_sensitive 敏感词/隐私脱敏）
--   AP001 简体化+全半角+特殊符号：繁体「紅綠燈已經壞了」→「红绿灯已经坏了」、
--         全角「６點」→「6点」、emoji「🔥」删除、空格删除、重复标点「。。。」→「。」
--   AP002 口语归一化：「不晓得/咋/灰常」→「不知道/怎么/非常」、「？？？」→「？」、
--         重复语气词「啊啊啊」→「啊」
--   AP003 隐私脱敏：正文手机号 13800002003 → 138****2003、
--         身份证号 130302195908152314 → 身份证号***
--   AP004 敏感词替换：辱骂词「傻逼/滚蛋/狗屁」→ ***，替换后不影响事件拆分与分类
--   验证口径：清洗在 Python detect 入口执行，子诉求 content 即清洗后文本；
--   同源组（AP011+）不掺入清洗要素，保证归并相似度判定稳定
--
-- 分类精准性：每个事件文本均包含权重 ≥1.20 的强关键词（classify_rule 阈值：
--   命中 1 个权重 ≥1.20 即判定），覆盖全部 8 个一级分类
--
-- 使用：每次执行先清空诉求域与工单域，再重新插入父诉求（20 条，见 一/二 部分）；
--       子诉求由前端「拆分所选」生成，工单由「生成工单」生成；本文件可重复执行（幂等重置）
-- ============================================================================

-- ============================================================================
-- 【执行语义】每次执行都会先清理「工单域 + 诉求域」，再重新插入父诉求：
--   ① 删除工单域（按依赖顺序：关联子表 → 日志 → 三套工单表）
--   ② 删除诉求域（先子诉求防孤儿 → 再父诉求）
--   ③ 插入 20 条父诉求（见 一/二 部分）
-- 目的：保证「拆分所选」「生成工单」测试可重复执行、结果可预期（幂等重置）。
-- 注意：本文件会删除已有的父诉求/子诉求/工单数据，仅适合测试库。
-- ============================================================================
-- ① 清理工单域：先删关联子表与日志，再删三套工单表（与三端数据解耦表结构对应）
DELETE FROM order_dispose_attachment;  -- 处置凭证附件
DELETE FROM order_evaluate;            -- 满意度评价
DELETE FROM order_delay_apply;         -- 延期申请
DELETE FROM admin_order_log;           -- 流转日志（管理端）
DELETE FROM dept_order_log;            -- 流转日志（部门端）
DELETE FROM street_order_log;          -- 流转日志（街道办端）
DELETE FROM work_order_appeal;         -- 工单-子诉求归并关联
DELETE FROM admin_order_archive;       -- 终审归档快照（独立归档表）
DELETE FROM admin_work_order;          -- 工单表（管理端全量）
DELETE FROM dept_work_order;           -- 工单表（部门端副本）
DELETE FROM street_work_order;         -- 工单表（街道办端副本）
-- ② 清理诉求域：先删子诉求（防孤儿）再删父诉求
DELETE FROM appeal_child;
DELETE FROM citizen_appeal;           -- 市民端诉求提交记录（独立落库）
DELETE FROM appeal_parent;

-- ==================== 一、多事件跨类拆分（6条） ====================
-- 预期：AP001 拆 3 条 / AP002 拆 2 条 / AP003 拆 2 条 / AP004 拆 2 条
--       AP005 拆 3 条 / AP006 拆 2 条，共 14 条子诉求

-- AP001：违章搭建(URBAN/违章建筑) + 红绿灯故障(TRAFFIC/交通信号灯) + 烧烤油烟(ENVIRONMENT/油烟污染)
INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000001', NULL, '海港区建设大街东侧有临街商铺私自违章搭建铁皮房经营，把人行道占了近一半，还遮挡了消防通道，存在严重安全隐患，多次反映无人处理。另外建设大街与文化路交叉口的紅綠燈已經壞了 三天，一直不亮，早晚高峰车辆行人抢道，秩序非常混乱，过马路提心吊胆。路口东北角还有几家烧烤店，每天晚上６點開始營業到凌晨，🔥油烟直接往楼上居民家里飘🔥，窗户都不敢开，油烟味特别大。。。', '张建国', '13800002001', '河北省', '秦皇岛市', '海港区', '东环路街道', '文化路社区', '建设大街12号临街区域', 0, 'TITLE_PENDING', 'URBAN', 0, DATE_SUB(NOW(), INTERVAL 1 HOUR));

-- AP002：电梯频繁故障(HOUSING/物业管理) + 频繁停电(PUBLIC/供电)
INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000002', NULL, '我们森林逸城小区12栋的电梯最近一个月频繁故障，坏了七八次，有邻居被困了半个多小时，物业每次都只是简单修修，不晓得咋彻底解决，过几天又坏，安全隐患灰常大，希望相关部门督促物业彻底检修。另外小区最近两个月频繁停电，几乎每周都要停一两次，每次两三个小时，家里的电器都烧坏了两个，供电部门说是线路老化需要改造，为啥一直没人来处理，烦死了啊啊啊！！！', '李美华', '13800002002', '河北省', '秦皇岛市', '海港区', '东环路街道', '森林逸城', '12栋单元楼及小区配电设施', 0, 'TITLE_PENDING', 'HOUSING', 0, DATE_SUB(NOW(), INTERVAL 2 HOUR));

-- AP003：拖欠工资(LABOR/拖欠工资) + 医保报销受阻(HEALTH/医保报销)
INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000003', NULL, '我在海港区一家餐饮公司上班，公司拖欠工资已经三个月了，老板一直推脱说资金周转困难，我们十几个员工多次讨薪无门，联系电话13800002003，请求劳动监察部门介入处理。另外我父亲今年65岁，在海港区医保中心办理医保报销时，因为系统里查不到2019年的部分缴费记录（父亲身份证号130302195908152314），材料被退回了三次，来回跑了快一个月还没办成，老人看病等钱用，希望医保部门帮忙核查清楚。', '王大伟', '13800002003', '河北省', '秦皇岛市', '海港区', '东环路街道', '工人里社区', '工人里小区3栋', 0, 'TITLE_PENDING', 'LABOR', 0, DATE_SUB(NOW(), INTERVAL 3 HOUR));

-- AP004：市场缺斤少两(MARKET/消费维权) + 加工店噪音扰民(ENVIRONMENT/噪音污染)
INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000004', NULL, '海港区天桥市场有水产商户缺斤少两，上周买了一斤虾回家一称只有七两，找商家理论对方态度还非常恶劣，骂我们傻逼还让我们滚蛋，说爱去哪告去哪告，要求市场监管部门查处这种欺诈行为。另外市场后面还有一家金属加工店，每天从早上七点开始切割钢材，噪音扰民特别严重，一直响到晚上七八点，家里老人心脏不好，白天根本没法休息，这家狗屁加工店多次交涉无果。', '赵丽娟', '13800002004', '河北省', '秦皇岛市', '海港区', '东环路街道', '天桥市场周边', '天桥市场水产区及后街加工店', 0, 'TITLE_PENDING', 'MARKET', 0, DATE_SUB(NOW(), INTERVAL 4 HOUR));

-- AP005：公交频繁脱班(TRAFFIC/公共交通) + 垃圾清运不及时(URBAN/垃圾清运) + 药店违规售药(MARKET/药品监管)
INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000005', NULL, '海港区河北大街的6路公交最近频繁脱班，站牌上写着十分钟一班，实际等半小时都来不了车，早高峰上班族经常迟到，向公交公司反映多次没有改善，希望交通部门督促整改。另外我们小区的垃圾清运也特别不及时，垃圾桶满了三天都没人清运，垃圾堆得到处都是，夏天臭气熏天，物业和环卫互相推诿。还有小区门口的那家药店，没有处方也卖处方药，还向老人推荐高价保健品，怀疑违规经营，希望药监部门查处。', '孙志明', '13800002005', '河北省', '秦皇岛市', '海港区', '东环路街道', '桥东里社区', '河北大街沿线及小区门口药店', 0, 'TITLE_PENDING', 'OTHER', 0, DATE_SUB(NOW(), INTERVAL 4 HOUR));

-- AP006：新房墙面漏水(HOUSING/房屋质量) + 社区医院医生态度差(HEALTH/医疗服务)
INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000006', NULL, '去年在海港区购买的新房，装修完入住不到半年，主卧和客厅墙面就出现多处开裂，下雨天还渗水漏水，找开发商维修，对方推诿说是装修破坏了墙体，拒绝负责，房屋质量问题实在让人头疼，希望住建部门介入鉴定处理。另外带孩子去社区医院看病，接诊的医生态度特别差，问什么都很不耐烦，连病情都不仔细看就赶着开药，家长敢怒不敢言，希望卫生部门管一管医生态度问题。', '钱晓燕', '13800002006', '河北省', '秦皇岛市', '海港区', '东环路街道', '东环里社区', '新交付小区及社区医院', 0, 'TITLE_PENDING', 'HOUSING', 0, DATE_SUB(NOW(), INTERVAL 4 HOUR));

-- ==================== 二、同源合并·关注度归并（14条） ====================

-- ---- 组1：文化路小学门口占道经营 × 5 条（AP011~015，预期归并为 1 张工单，关注度 5 → 一级） ----
-- 五条内容均为「文化路实验小学门口流动摊贩占道经营」，同分类 URBAN + 同辖区海港区 + 语义相似度高

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000011', NULL, '海港区文化路实验小学门口，每天放学时间都有大量流动摊贩占道经营，卖零食卖玩具，把校门口两侧的人行道和盲道全部占满了，学生们只能绕到机动车道上行走，非常危险，请城管部门尽快整治。', '刘志强', '13800002011', '河北省', '秦皇岛市', '海港区', '东环路街道', '文化里社区', '文化路实验小学门口', 0, 'TITLE_PENDING', 'URBAN', 0, DATE_SUB(NOW(), INTERVAL 5 HOUR));

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000012', NULL, '文化路实验小学门口流动摊贩占道经营问题非常严重，上下学高峰期校门口人行道完全无法通行，孩子只能在车流里穿行，家长们都很担心安全，希望城管部门尽快治理占道经营。', '周文博', '13800002012', '河北省', '秦皇岛市', '海港区', '东环路街道', '文化里社区', '文化路实验小学门口', 0, 'TITLE_PENDING', 'URBAN', 0, DATE_SUB(NOW(), INTERVAL 6 HOUR));

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000013', NULL, '反映文化路实验小学门口占道经营问题：每天下午四点到六点，流动摊贩就把校门口两边人行道摆满了，严重影响学生通行，多次反映一直没有根治，建议城管部门设置临时疏导点，彻底解决占道经营。', '林小红', '13800002013', '河北省', '秦皇岛市', '海港区', '东环路街道', '文化里社区', '文化路实验小学门口', 0, 'TITLE_PENDING', 'URBAN', 0, DATE_SUB(NOW(), INTERVAL 7 HOUR));

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000014', NULL, '文化路实验小学周边流动摊贩占道经营屡禁不止，卖小吃的摊子把校门口人行道全堵死了，接送孩子的家长和学生只能绕行机动车道，太危险了，请城管部门加大整治力度，取缔占道经营。', '吴桂芳', '13800002014', '河北省', '秦皇岛市', '海港区', '东环路街道', '文化里社区', '文化路实验小学门口', 0, 'TITLE_PENDING', 'URBAN', 0, DATE_SUB(NOW(), INTERVAL 8 HOUR));

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000015', NULL, '文化路实验小学门口每天傍晚都有几十个摊位占道经营，人行道被完全占满，行人和放学的学生被迫走到机动车道上，存在很大安全隐患，强烈要求城管部门取缔这里的占道经营。', '郑国栋', '13800002015', '河北省', '秦皇岛市', '海港区', '东环路街道', '文化里社区', '文化路实验小学门口', 0, 'TITLE_PENDING', 'URBAN', 0, DATE_SUB(NOW(), INTERVAL 9 HOUR));

-- ---- 组2：燕山小区频繁停水 × 3 条（预期归并为 1 张工单，关注度 3 → 二级） ----
-- 三条内容均为「燕山小区频繁停水」，同分类 PUBLIC + 同辖区海港区 + 语义相似度高

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000016', NULL, '燕山小区本月已经停水三次了，每次都是一整天，家里做饭洗澡都成问题，物业说是供水管网老化，希望供水公司尽快安排检修，解决频繁停水问题。', '孙丽华', '13800002016', '河北省', '秦皇岛市', '海港区', '东环路街道', '燕山小区', '燕山小区全区', 0, 'TITLE_PENDING', 'PUBLIC', 0, DATE_SUB(NOW(), INTERVAL 10 HOUR));

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000017', NULL, '燕山小区又停水了，这已经是这个月第三次，从早上六点一直停到现在，居民做饭洗澡冲厕所都成问题，生活受到严重影响，请相关部门督促供水公司尽快恢复供水并根治频繁停水。', '杨建国', '13800002017', '河北省', '秦皇岛市', '海港区', '东环路街道', '燕山小区', '燕山小区全区', 0, 'TITLE_PENDING', 'PUBLIC', 0, DATE_SUB(NOW(), INTERVAL 11 HOUR));

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000018', NULL, '我们燕山小区频繁停水，几乎每周都要停一次，每次大半天，家里老人小孩都受不了，物业推说让找供水公司，希望市政供水部门能彻底检修老化管网，解决停水问题。', '马国良', '13800002018', '河北省', '秦皇岛市', '海港区', '东环路街道', '燕山小区', '燕山小区全区', 0, 'TITLE_PENDING', 'PUBLIC', 0, DATE_SUB(NOW(), INTERVAL 12 HOUR));

-- ---- 组3：建筑工地欠薪 × 4 条（预期归并为 1 张工单，关注度 4 → 二级） ----
-- 四条内容均为「北部工业区建筑工地拖欠工资」，同分类 LABOR + 同辖区海港区 + 语义相似度高

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000019', NULL, '我们在海港区北部工业区一个建筑工地干活，工地拖欠工资已经三个月了，二十多个工友多次找包工头讨薪，对方一直推脱说上面工程款没下来，眼看着要开学了，孩子的学费都没着落，请求劳动监察部门介入帮我们讨回拖欠工资。', '何美丽', '13800002019', '河北省', '秦皇岛市', '海港区', '东环路街道', '工业区社区', '北部工业区建筑工地项目部', 0, 'TITLE_PENDING', 'LABOR', 0, DATE_SUB(NOW(), INTERVAL 13 HOUR));

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000020', NULL, '海港区北部工业区的建筑工地欠薪问题严重，我们班组干了半年，包工头一直不发工资，说是要等工程验收，但工地早就停工了也没人管，工友们都是家里等着钱用，讨薪无门只能求助政府部门，希望能帮我们要回血汗钱。', '唐先生', '13800002020', '河北省', '秦皇岛市', '海港区', '东环路街道', '工业区社区', '北部工业区建筑工地项目部', 0, 'TITLE_PENDING', 'LABOR', 0, DATE_SUB(NOW(), INTERVAL 14 HOUR));

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000021', NULL, '反映海港区北部工业区建筑工地拖欠农民工工资问题：工地从年初开工到现在只发过一次生活费，承诺的月结工资一直没兑现，工友们找过项目部很多次都被搪塞，请求劳动监察部门尽快查处拖欠工资行为，帮农民工维权。', '冯春花', '13800002021', '河北省', '秦皇岛市', '海港区', '东环路街道', '工业区社区', '北部工业区建筑工地项目部', 0, 'TITLE_PENDING', 'LABOR', 0, DATE_SUB(NOW(), INTERVAL 15 HOUR));

-- ---- 组4：金梦海湾小区电梯故障 × 3 条（预期归并为 1 张工单，关注度 3 → 二级） ----
-- 三条内容均为「金梦海湾小区电梯频繁故障」，同分类 HOUSING + 同辖区海港区 + 语义相似度高

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000023', NULL, '金梦海湾小区8栋的电梯频繁故障，这个月已经坏了五次，上周还有居民被困在电梯里半个多小时，物业每次都只是叫人简单修一下，过几天又坏，电梯故障这么频繁，安全隐患实在太大了，希望相关部门督促物业彻底检修更换部件。', '曹建华', '13800002023', '河北省', '秦皇岛市', '海港区', '东环路街道', '金梦海湾', '8栋电梯', 0, 'TITLE_PENDING', 'HOUSING', 0, DATE_SUB(NOW(), INTERVAL 17 HOUR));

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000024', NULL, '金梦海湾小区8栋电梯又坏了，这已经是本月第五次故障，家里老人八十多岁，电梯一坏就只能爬楼梯，住在十楼根本下不来，物业维修总是治标不治本，业主们都很担心哪天出安全事故，强烈要求物业彻底检修电梯。', '潘秀英', '13800002024', '河北省', '秦皇岛市', '海港区', '东环路街道', '金梦海湾', '8栋电梯', 0, 'TITLE_PENDING', 'HOUSING', 0, DATE_SUB(NOW(), INTERVAL 18 HOUR));

INSERT INTO appeal_parent (appeal_no, title, content, citizen_name, citizen_phone, province, city, district, street, community, address_detail, broad_flag, status, category, child_count, create_time) VALUES
('AP202608311000025', NULL, '反映金梦海湾小区8栋电梯频繁故障问题：电梯隔三差五就坏，还经常出现下坠感、门关不严的情况，物业每次都说修好了但没过几天又出故障，业主们天天提心吊胆，希望质监部门介入检查电梯安全，督促物业彻底解决故障问题。', '石磊', '13800002025', '河北省', '秦皇岛市', '海港区', '东环路街道', '金梦海湾', '8栋电梯', 0, 'TITLE_PENDING', 'HOUSING', 0, DATE_SUB(NOW(), INTERVAL 19 HOUR));

-- ============================================================================
-- 测试步骤：
--   1. 执行本文件（可重复执行）：每次执行都会先清空工单域与诉求域，
--      再重新插入 20 条父诉求（幂等重置，适合测试库）
--   2. 管理端「诉求管理」勾选全部 20 条父诉求 → 拆分所选 → 验证拆出 28 条子诉求
--   3. 勾选全部子诉求 → 生成工单 → 验证共 18 张工单：
--      多事件组 14 张独立工单 + 归并组 4 张（关注度 5/3/3/3，等级 一级/二级×3）
--   4. 清洗验证：查看 AP001~004 拆出的子诉求 content——繁简/全半角已统一、emoji
--      与多余空白已删除、口语已归一化、手机号 138****2003 / 身份证号***已脱敏、
--      辱骂词已替换为 ***；再在「系统日志」查看 module=appeal-split 的清洗细则审计
--   5. 重复执行本文件 = 回到初始测试状态（清理 + 重插 20 条父诉求）
-- ============================================================================
