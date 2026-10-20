SET NAMES utf8mb4;
-- ============================================================================
-- 12345政务热线 — 分类体系种子数据（AI 智能分类的知识基础）
-- 执行前提：先执行 00-init-all.sql 建库建表
-- 内容：一级分类 → 二级标签 → 分类关键词 → 分类规则 → RAG 知识库样本
-- 表间依赖：classify_keyword/rule/rag_sample 通过 category_id 引用 classify_category，
--   必须在本文件内按上述顺序执行，不可拆开单独执行后半部分
-- ============================================================================

USE hotline_12345;

-- ==================== 零、清理旧分类数据（幂等，防重复插入/唯一键冲突） ====================
-- 删除顺序：先删引用方（keyword/rule/rag_sample → category → primary），防 category_id 悬空
-- ★ 注意：重复执行会把分类域整体重置为种子状态，页面「分类规则维护」中改过的数据会被覆盖；
--   若曾启用 RAG 样本，建议同步清理 Redis 向量库（rag:v:*）避免检索到已删除样本
DELETE FROM classify_keyword;   -- 分类关键词（引用 category_id）
DELETE FROM classify_rule;      -- 分类规则（引用 category_id）
DELETE FROM rag_sample;         -- RAG 知识库样本（引用 category_id）
DELETE FROM classify_category;  -- 二级标签（显式 id 1~40）
DELETE FROM classify_primary;   -- 一级分类（8 大类）

-- ==================== 一、一级分类（8 大类） ====================
-- 需求 10.3(1)：分类代码 + 名称 + 分派部门
INSERT INTO classify_primary (code, name, department, sort_order, status) VALUES
('URBAN',       '城市管理', '城管部门',     1, 'ENABLED'),
('TRAFFIC',     '交通管理', '交通部门',     2, 'ENABLED'),
('MARKET',      '市场监管', '市场监管部门', 3, 'ENABLED'),
('ENVIRONMENT', '环境保护', '生态环境部门', 4, 'ENABLED'),
('HOUSING',     '住房保障', '住建部门',     5, 'ENABLED'),
('LABOR',       '劳动保障', '人社部门',     6, 'ENABLED'),
('PUBLIC',      '公共服务', '市政部门',     7, 'ENABLED'),
('HEALTH',      '医疗卫生', '卫健部门',     8, 'ENABLED');

-- ==================== 二、二级标签（8 大类 × 5 = 40 条） ====================
-- 显式 id 1~40，后续新增标签不带 id 自动从 41 递增
INSERT INTO classify_category (id, primary_category, sub_label, sort_order) VALUES
-- 城市管理（城管 URBAN）
(1,  'URBAN',       '占道经营',       1),
(2,  'URBAN',       '乱搭乱建',       2),
(3,  'URBAN',       '市容环境卫生',   3),
(4,  'URBAN',       '噪音扰民',       4),
(5,  'URBAN',       '户外广告牌匾',   5),
-- 交通管理（交通 TRAFFIC）
(6,  'TRAFFIC',     '交通拥堵',       1),
(7,  'TRAFFIC',     '公交出行服务',   2),
(8,  'TRAFFIC',     '道路设施破损',   3),
(9,  'TRAFFIC',     '停车秩序问题',   4),
(10, 'TRAFFIC',     '交通信号设施',   5),
-- 市场监管（市场 MARKET）
(11, 'MARKET',      '食品安全',       1),
(12, 'MARKET',      '价格与收费投诉', 2),
(13, 'MARKET',      '假冒伪劣商品',   3),
(14, 'MARKET',      '消费维权退换',   4),
(15, 'MARKET',      '预付式消费纠纷', 5),
-- 环境保护（环境 ENVIRONMENT）
(16, 'ENVIRONMENT', '大气污染与异味', 1),
(17, 'ENVIRONMENT', '水环境污染',     2),
(18, 'ENVIRONMENT', '噪音污染',       3),
(19, 'ENVIRONMENT', '垃圾与固废污染', 4),
(20, 'ENVIRONMENT', '违法排污',       5),
-- 住房保障（住建 HOUSING）
(21, 'HOUSING',     '物业服务纠纷',   1),
(22, 'HOUSING',     '房屋质量与漏水', 2),
(23, 'HOUSING',     '延期交房与烂尾', 3),
(24, 'HOUSING',     '拆迁安置补偿',   4),
(25, 'HOUSING',     '保障房申请',     5),
-- 劳动保障（人社 LABOR）
(26, 'LABOR',       '拖欠工资',       1),
(27, 'LABOR',       '劳动合同纠纷',   2),
(28, 'LABOR',       '社保缴纳问题',   3),
(29, 'LABOR',       '工伤认定',       4),
(30, 'LABOR',       '就业服务',       5),
-- 公共服务（市政 PUBLIC）
(31, 'PUBLIC',      '停水停电',       1),
(32, 'PUBLIC',      '供暖供热',       2),
(33, 'PUBLIC',      '供气服务',       3),
(34, 'PUBLIC',      '市政设施损坏',   4),
(35, 'PUBLIC',      '公共服务咨询',   5),
-- 医疗卫生（卫健 HEALTH）
(36, 'HEALTH',      '医疗服务投诉',   1),
(37, 'HEALTH',      '医疗收费问题',   2),
(38, 'HEALTH',      '医疗纠纷',       3),
(39, 'HEALTH',      '药品安全',       4),
(40, 'HEALTH',      '预防接种服务',   5);

-- ==================== 三、分类关键词（8 大类） ====================
-- 需求 8.3(6)：权重越高匹配优先级越高；按一级分类取该类首个二级标签挂载关键词
INSERT INTO classify_keyword (category_id, keyword, weight, status)
SELECT c.id, k.keyword, k.weight, 'ENABLED'
FROM (SELECT 'URBAN' AS cat, '占道经营' AS keyword, 1.50 AS weight
      UNION ALL SELECT 'URBAN', '乱摆摊', 1.20
      UNION ALL SELECT 'URBAN', '乱搭乱建', 1.50
      UNION ALL SELECT 'URBAN', '违章搭建', 1.50
      UNION ALL SELECT 'URBAN', '违规建筑', 1.30
      UNION ALL SELECT 'URBAN', '路边摊', 1.20
      UNION ALL SELECT 'URBAN', '店外经营', 1.30
      UNION ALL SELECT 'URBAN', '流动摊贩', 1.30
      UNION ALL SELECT 'URBAN', '私搭乱建', 1.40
      UNION ALL SELECT 'URBAN', '违建', 1.30
      UNION ALL SELECT 'URBAN', '占道', 1.20
      UNION ALL SELECT 'URBAN', '施工噪音', 1.40
      UNION ALL SELECT 'URBAN', '打桩', 1.30
      UNION ALL SELECT 'URBAN', '夜间施工', 1.30
      UNION ALL SELECT 'URBAN', '渣土车', 1.10
      UNION ALL SELECT 'URBAN', '扬尘', 1.10
      UNION ALL SELECT 'URBAN', '建筑垃圾', 1.20
      UNION ALL SELECT 'URBAN', '乱贴广告', 1.10
      UNION ALL SELECT 'URBAN', '市容', 1.00
      UNION ALL SELECT 'TRAFFIC', '红绿灯', 1.50
      UNION ALL SELECT 'TRAFFIC', '交通信号', 1.30
      UNION ALL SELECT 'TRAFFIC', '信号灯', 1.40
      UNION ALL SELECT 'TRAFFIC', '交通灯', 1.40
      UNION ALL SELECT 'TRAFFIC', '拥堵', 1.20
      UNION ALL SELECT 'TRAFFIC', '违章停车', 1.30
      UNION ALL SELECT 'TRAFFIC', '乱停车', 1.20
      UNION ALL SELECT 'TRAFFIC', '斑马线', 1.10
      UNION ALL SELECT 'TRAFFIC', '公交', 1.00
      UNION ALL SELECT 'TRAFFIC', '地铁', 0.90
      UNION ALL SELECT 'TRAFFIC', '路面破损', 1.20
      UNION ALL SELECT 'TRAFFIC', '道路坑洼', 1.20
      UNION ALL SELECT 'TRAFFIC', '井盖', 1.10
      UNION ALL SELECT 'TRAFFIC', '交通护栏', 1.10
      UNION ALL SELECT 'TRAFFIC', '人行道', 1.00
      UNION ALL SELECT 'TRAFFIC', '非机动车道', 1.10
      UNION ALL SELECT 'MARKET', '消费维权', 1.40
      UNION ALL SELECT 'MARKET', '虚假宣传', 1.30
      UNION ALL SELECT 'MARKET', '虚假广告', 1.20
      UNION ALL SELECT 'MARKET', '消费纠纷', 1.20
      UNION ALL SELECT 'MARKET', '缺斤少两', 1.30
      UNION ALL SELECT 'MARKET', '哄抬物价', 1.30
      UNION ALL SELECT 'MARKET', '假冒伪劣', 1.40
      UNION ALL SELECT 'MARKET', '假冒', 1.30
      UNION ALL SELECT 'MARKET', '三无产品', 1.30
      UNION ALL SELECT 'MARKET', '无证经营', 1.20
      UNION ALL SELECT 'MARKET', '欺诈', 1.20
      UNION ALL SELECT 'MARKET', '过期', 1.20
      UNION ALL SELECT 'MARKET', '食品', 1.10
      UNION ALL SELECT 'MARKET', '药品', 1.20
      UNION ALL SELECT 'MARKET', '退换货', 1.10
      UNION ALL SELECT 'ENVIRONMENT', '噪音扰民', 1.30
      UNION ALL SELECT 'ENVIRONMENT', '油烟扰民', 1.30
      UNION ALL SELECT 'ENVIRONMENT', '油烟', 1.30
      UNION ALL SELECT 'ENVIRONMENT', '烧烤', 1.20
      UNION ALL SELECT 'ENVIRONMENT', '噪声', 1.20
      UNION ALL SELECT 'ENVIRONMENT', '污染', 1.20
      UNION ALL SELECT 'ENVIRONMENT', '空气污染', 1.20
      UNION ALL SELECT 'ENVIRONMENT', '排污', 1.30
      UNION ALL SELECT 'ENVIRONMENT', '废水', 1.20
      UNION ALL SELECT 'ENVIRONMENT', '污水排放', 1.30
      UNION ALL SELECT 'ENVIRONMENT', '废气', 1.30
      UNION ALL SELECT 'ENVIRONMENT', '异味', 1.10
      UNION ALL SELECT 'ENVIRONMENT', '焚烧', 1.10
      UNION ALL SELECT 'HOUSING', '物业费', 1.50
      UNION ALL SELECT 'HOUSING', '物业纠纷', 1.30
      UNION ALL SELECT 'HOUSING', '物业不作为', 1.20
      UNION ALL SELECT 'HOUSING', '小区管理', 1.00
      UNION ALL SELECT 'HOUSING', '电梯故障', 1.20
      UNION ALL SELECT 'HOUSING', '电梯', 1.10
      UNION ALL SELECT 'HOUSING', '房屋漏水', 1.30
      UNION ALL SELECT 'HOUSING', '漏水', 1.20
      UNION ALL SELECT 'HOUSING', '房屋质量', 1.20
      UNION ALL SELECT 'HOUSING', '乱收费', 1.20
      UNION ALL SELECT 'HOUSING', '公积金', 1.20
      UNION ALL SELECT 'HOUSING', '拆迁', 1.20
      UNION ALL SELECT 'HOUSING', '交房', 1.20
      UNION ALL SELECT 'HOUSING', '房产证', 1.20
      UNION ALL SELECT 'LABOR', '拖欠工资', 1.50
      UNION ALL SELECT 'LABOR', '欠薪', 1.50
      UNION ALL SELECT 'LABOR', '讨薪', 1.40
      UNION ALL SELECT 'LABOR', '克扣', 1.30
      UNION ALL SELECT 'LABOR', '加班费', 1.20
      UNION ALL SELECT 'LABOR', '社保', 1.20
      UNION ALL SELECT 'LABOR', '五险一金', 1.20
      UNION ALL SELECT 'LABOR', '工伤', 1.30
      UNION ALL SELECT 'LABOR', '工伤赔偿', 1.30
      UNION ALL SELECT 'LABOR', '劳动合同', 1.30
      UNION ALL SELECT 'LABOR', '劳动仲裁', 1.20
      UNION ALL SELECT 'LABOR', '辞退', 1.20
      UNION ALL SELECT 'LABOR', '补偿金', 1.20
      UNION ALL SELECT 'PUBLIC', '停水', 1.50
      UNION ALL SELECT 'PUBLIC', '停电', 1.50
      UNION ALL SELECT 'PUBLIC', '断水', 1.30
      UNION ALL SELECT 'PUBLIC', '供水', 1.20
      UNION ALL SELECT 'PUBLIC', '水压低', 1.20
      UNION ALL SELECT 'PUBLIC', '水质', 1.10
      UNION ALL SELECT 'PUBLIC', '供暖', 1.30
      UNION ALL SELECT 'PUBLIC', '暖气不热', 1.30
      UNION ALL SELECT 'PUBLIC', '燃气', 1.30
      UNION ALL SELECT 'PUBLIC', '燃气泄漏', 1.40
      UNION ALL SELECT 'PUBLIC', '电费', 1.10
      UNION ALL SELECT 'PUBLIC', '宽带', 1.10
      UNION ALL SELECT 'PUBLIC', '网络', 1.00
      UNION ALL SELECT 'PUBLIC', '通讯', 1.00
      UNION ALL SELECT 'HEALTH', '医院', 1.00
      UNION ALL SELECT 'HEALTH', '看病难', 1.10
      UNION ALL SELECT 'HEALTH', '挂号难', 1.10
      UNION ALL SELECT 'HEALTH', '医生态度', 1.10
      UNION ALL SELECT 'HEALTH', '医疗纠纷', 1.20
      UNION ALL SELECT 'HEALTH', '医保', 1.20
      UNION ALL SELECT 'HEALTH', '报销', 1.10
      UNION ALL SELECT 'HEALTH', '疫苗', 1.20
      UNION ALL SELECT 'HEALTH', '传染病', 1.20
      UNION ALL SELECT 'HEALTH', '防疫', 1.10
      UNION ALL SELECT 'HEALTH', '诊所', 1.10
      UNION ALL SELECT 'HEALTH', '看病', 1.00) k
JOIN (SELECT MIN(id) AS id, primary_category FROM classify_category GROUP BY primary_category) c
  ON c.primary_category = k.cat;

-- ==================== 四、分类规则（命中阈值判定） ====================
-- 需求 10.3(4)：命中 1 个权重 ≥1.20 的强关键词即判定，避免泛词误判
INSERT INTO classify_rule (category_id, hit_count_threshold, weight_sum_threshold)
SELECT id, 1, 1.20 FROM (SELECT MIN(id) AS id, primary_category FROM classify_category GROUP BY primary_category) c;

-- ==================== 五、RAG 知识库样本 ====================
-- 需求 8.3(4)：每类典型诉求，供向量检索语义分类参考
INSERT INTO rag_sample (category_id, content, source, quality_level, status)
SELECT c.id, k.content, 'EXPERT', 'P1', 'ENABLED'
FROM (SELECT 'URBAN' AS cat, '小区门口每天早上有大量流动摊贩占道经营，卖菜卖水果，把人行道都占满了，行人只能走到机动车道上，非常危险。' AS content
      UNION ALL SELECT 'URBAN', '对面工地凌晨还在施工，打桩声和渣土车噪音非常大，严重影响周边居民休息。'
      UNION ALL SELECT 'TRAFFIC', '燕山大学门口红绿灯时间太短，学生过马路要等很久，建议延长绿灯时间。'
      UNION ALL SELECT 'TRAFFIC', '小区周边违章停车严重，道路两侧停满车，影响车辆和行人正常通行。'
      UNION ALL SELECT 'MARKET', '菜市场有商户缺斤少两，消费者投诉多次无果，要求市场监管部门查处。'
      UNION ALL SELECT 'MARKET', '网上购买的商品是假冒伪劣产品，商家虚假宣传，要求退款并赔偿。'
      UNION ALL SELECT 'ENVIRONMENT', '楼下烧烤摊每晚营业到凌晨，油烟直排，味道非常大，窗户都不敢开。'
      UNION ALL SELECT 'ENVIRONMENT', '附近工厂排放废水废气，河水变黑发臭，空气中有异味，影响周边居民健康。'
      UNION ALL SELECT 'HOUSING', '小区物业不作为，电梯故障半个月无人维修，物业费照收，业主投诉无门。'
      UNION ALL SELECT 'HOUSING', '新房交付后墙面多处开裂漏水，开发商推诿不处理，要求住建部门介入。'
      UNION ALL SELECT 'LABOR', '公司拖欠三个月工资不发，老板跑路，员工讨薪无门，请求劳动部门介入。'
      UNION ALL SELECT 'LABOR', '工作期间受伤，公司不认工伤，拒绝赔偿，要求人社部门认定工伤。'
      UNION ALL SELECT 'PUBLIC', '小区连续停水三天，居民生活受严重影响，多次反映供水公司无人处理。'
      UNION ALL SELECT 'PUBLIC', '冬季供暖不足，暖气片不热，室内温度很低，老人小孩冻得受不了。'
      UNION ALL SELECT 'HEALTH', '大医院挂号难，排队时间长，医生态度差，看病难问题突出。'
      UNION ALL SELECT 'HEALTH', '医保异地报销流程复杂，报销比例低，希望能简化流程。') k
JOIN (SELECT MIN(id) AS id, primary_category FROM classify_category GROUP BY primary_category) c
  ON c.primary_category = k.cat;

-- ============================================================================
-- 说明：
--   1) 本文件执行后，Java 后端启动时会将分类关键词/规则同步写入 Redis 分类知识桥
--      （cls:keywords/cls:rules/cls:labels/cls:primary），Python AI 服务从 Redis 读取
--   2) RAG 样本需向量向入库：样本写入 rag:pending 队列后由 Python 后台线程
--      Embedding 写入 Redis Stack 向量索引（rag_idx），无需手动处理
--   3) 后续通过管理端「分类规则维护」增删改时，Java 侧自动同步 Redis，不再改本文件
-- ============================================================================
