SET NAMES utf8mb4;
-- ============================================================================
-- 预警管理模块测试数据（配合"立即扫描"验证预警触发）
-- 维度：民生类（LIVELIHOOD）全新维度，避免与现有 URBAN/PROPERTY 规则/工单冲突
-- 说明：清理条件使用纯 ASCII 保证幂等，可重复执行
-- ============================================================================

-- 0. 清理上次测试残留（幂等）：先清空全部预警记录（防孤儿，避免任何规则残留触发重复预警），再删工单与规则
DELETE FROM warning_record;
DELETE FROM admin_work_order  WHERE order_no LIKE 'TST20260829W%';
DELETE FROM dept_work_order   WHERE order_no LIKE 'TST20260829W%';
DELETE FROM street_work_order WHERE order_no LIKE 'TST20260829W%';
DELETE FROM admin_order_archive WHERE order_no LIKE 'TST20260829W%';
DELETE FROM warning_rule WHERE category = 'LIVELIHOOD';
DELETE FROM warning_case;
-- 56 部门复合推送测试规则（幂等清理，可重复执行）
DELETE FROM warning_rule WHERE rule_name LIKE '%56部门复合推送测试%';

-- 1. 新增预警规则：单日民生类诉求关注度≥5 即触发 SEVERE 预警（辖区/类别均不限=全部56部门）
INSERT INTO warning_rule (rule_name, time_dimension, category, sub_category, region, count_threshold, warning_level, push_targets, push_counties, push_categories, status)
VALUES ('单日民生类诉求预警测试', 'DAY', 'LIVELIHOOD', NULL, '海港区', 5, 'SEVERE', NULL, NULL, NULL, 'ENABLED');

-- 2. 56 部门口径推送示例（双限制：辖区限制 × 部门类别限制，笛卡尔积展开）
--   示例A：海港区+昌黎县 的 城管+市场监管 四个部门可见
INSERT INTO warning_rule (rule_name, time_dimension, category, county_code, count_threshold, warning_level, push_targets, push_counties, push_categories, status)
VALUES ('56部门复合推送测试-区县指定', 'DAY', NULL, NULL, 5, 'SEVERE', NULL, '130302,130322', 'URBAN,MARKET', 'ENABLED');
--   示例B：全部区县的城管分类 + 管理端
INSERT INTO warning_rule (rule_name, time_dimension, category, county_code, count_threshold, warning_level, push_targets, push_counties, push_categories, status)
VALUES ('56部门复合推送测试-全市分类', 'DAY', 'URBAN', NULL, 5, 'SEVERE', NULL, NULL, 'URBAN', 'ENABLED');

INSERT INTO warning_rule (rule_name,time_dimension,category,sub_category,region,count_threshold,warning_level,push_targets,push_counties,push_categories,status) VALUES
('供暖供热诉求单日聚集预警','DAY','PUBLIC','供暖供热',NULL,3,'SEVERE',NULL,NULL,NULL,'ENABLED');

-- ---------- 2. 插入 7 条优秀处置案例（DISABLED，待界面启用） ----------

-- 【公共服务·供暖】→ 命中 WR01 (cat=PUBLIC)
INSERT INTO warning_case
(case_no, title, category, sub_category, region, county_code, county_name, content, measure_plan, measure_result, source, status)
VALUES
('WC20260910001', '供暖季集中供暖管道老化与缴费不畅投诉处置', 'PUBLIC', '供暖供热', '海港区', '130302', '海港区',
 '入冬后供暖问题集中爆发：供暖管道老化导致温度不达标、换热站运行噪音大扰民、燃气报装进度慢、供暖缴费渠道不畅，涉及海港区、昌黎县、山海关区等多个小区的居民，严重影响居民冬季正常生活和供暖质量。',
 '1. 24小时内约谈热力公司，核查一次管网出水温度与换热站运行参数；2. 组织网格员入户实测室温并建立台账，低温住户发放电暖器临时救济；3. 三日内完成重点小区老旧管网排查与换热站降噪改造；4. 优化线上缴费系统并增设线下网点，公布整改进度。',
 '五日内重点小区平均室温回升至20度以上，换热站噪音达标，线上缴费办理率提升至92%，同类供暖诉求量下降82%。',
 'MANUAL', 'DISABLED');

-- 【住房保障·物业服务纠纷】→ 命中 WR03（case_no 排第007，专门贴合"不作为/消防通道"）
INSERT INTO warning_case
(case_no, title, category, sub_category, region, county_code, county_name, content, measure_plan, measure_result, source, status)
VALUES
('WC20260910007', '昌黎县某小区物业不作为及消防通道堵塞集中投诉处置', 'HOUSING', '物业服务纠纷', '昌黎县', '130322', '昌黎县',
 '物业费涨价未征求业主意见、物业不作为服务差、绿化带被私自圈占种菜、消防通道堵塞问题集中爆发，物业公司与业主矛盾激化，涉及昌黎县、海港区、山海关区、北戴河区等多个小区，居民日常生活和生命财产安全受影响。',
 '1. 住建部门责令物业暂停调价程序，组织街道办、业委会、物业公司三方协商重新表决；2. 责令物业限期清理圈占绿化带和消防通道堵塞，消除安全隐患；3. 建立物业服务质量季度公示与业主评价机制；4. 对拒不整改的物业纳入失信黑名单并约谈企业负责人。',
 '物业费调价重新表决通过，绿化带与消防通道一周内清理完毕，物业投诉量下降85%，业委会监督机制落地执行。',
 'MANUAL', 'DISABLED');

-- 【住房保障·物业服务纠纷】→ 双保险 命中 WR03（原物业费调价案，保留）
INSERT INTO warning_case
(case_no, title, category, sub_category, region, county_code, county_name, content, measure_plan, measure_result, source, status)
VALUES
('WC20260910002', '海港区某小区物业费调价争议与电梯故障漏水问题综合处置', 'HOUSING', '物业服务纠纷', '海港区', '130302', '海港区',
 '海港区某小区物业公司未经业主大会同意单方面上调物业费，同时小区电梯频繁故障维修不及时、楼顶漏水长期未修、绿化带被私自圈占，业主多次协商无果后集中投诉，物业争端持续升级。',
 '1. 住建部门责令物业暂停调价程序，组织街道办、业委会、物业公司三方协商重新表决；2. 特种设备监管部门对电梯安全检查责令限期维保并公示维修记录；3. 启用维修基金绿色通道修缮漏水楼顶；4. 建立物业服务质量季度公示与业主评价机制。',
 '物业费调价程序重新表决通过合理方案，电梯故障率下降70%，漏水问题两周内修缮完毕，小区物业投诉量下降85%。',
 'MANUAL', 'DISABLED');

-- 【住房保障·延期交房与烂尾】备用（rule=24）
INSERT INTO warning_case
(case_no, title, category, sub_category, region, county_code, county_name, content, measure_plan, measure_result, source, status)
VALUES
('WC20260910003', '山海关区某楼盘延期交房涉及上百户购房者应急处置', 'HOUSING', '延期交房与烂尾', '山海关区', '130303', '山海关区',
 '山海关区某在建楼盘因开发商资金链紧张多次延期交房，涉及上百户购房者，购房者担心楼盘烂尾集中维权情绪激动，部分业主计划集体信访，诉求短时间内激增。',
 '1. 住建部门约谈开发商核实资金与施工情况，成立项目工作专班；2. 启用商品房预售资金监管账户优先保障工程施工；3. 每周向购房者公示施工进度建立业主沟通群；4. 对烂尾风险项目引导业主依法维权并做好稳定疏导。',
 '三个月内完成主体施工并分批交房，购房者集体信访事件妥善化解，同类投诉归零，未发生群体性事件。',
 'MANUAL', 'DISABLED');

-- 【市场监管·食品安全】备用（专项演示可选）
INSERT INTO warning_case
(case_no, title, category, sub_category, region, county_code, county_name, content, measure_plan, measure_result, source, status)
VALUES
('WC20260910004', '昌黎县夏季夜市食品安全隐患与食材卫生专项检查处置', 'MARKET', '食品安全', '昌黎县', '130322', '昌黎县',
 '昌黎县夏季夜市摊贩集中，居民反映部分摊位无证经营、食材来源不明、生熟混放存在食品安全隐患，高温天气下食物变质风险加大，短期内相关食品诉求持续上升。',
 '1. 市场监管部门连夜开展夜市食品摊贩专项检查，核查证照与进货台账；2. 对无证摊贩现场责令停业并引导办证；3. 组织摊主食品安全培训并推行摊位亮证经营；4. 建立夜市食品快检点每周抽检并公示。',
 '专项检查覆盖摊位120个，责令整改23家，证照持有率从61%提升至100%，当月食品安全投诉下降79%，未发生食品安全事故。',
 'MANUAL', 'DISABLED');

-- 【城市管理·占道经营】→ 命中 WR02 (cat=URBAN)
INSERT INTO warning_case
(case_no, title, category, sub_category, region, county_code, county_name, content, measure_plan, measure_result, source, status)
VALUES
('WC20260910005', '海港区夜市烧烤占道经营油烟噪音与流动摊贩综合整治', 'URBAN', '占道经营', '海港区', '130302', '海港区',
 '海港区、北戴河区等区域商户高音喇叭噪音扰民、夜市烧烤摊占道经营油烟扰民、早市流动摊贩占道影响通行及卫生死角清理问题集中，人车混行存在安全隐患，城管类诉求上升明显。',
 '1. 城管部门划定夜市规范经营区域，统一摊位界线与营业时段；2. 联合市场监管、环保部门对烧烤摊加装油烟净化设备并禁止高音喇叭；3. 增设疏导点安置流动摊贩；4. 建立每晚巡查与居民监督反馈机制。',
 '一个月内占道经营类投诉下降76%，油烟与噪音扰民投诉基本清零，夜市秩序与人车通行安全明显改善。',
 'MANUAL', 'DISABLED');

-- 【公共服务·环卫】备用（rule=21 通用）
INSERT INTO warning_case
(case_no, title, category, sub_category, region, county_code, county_name, content, measure_plan, measure_result, source, status)
VALUES
('WC20260910006', '卢龙县背街小巷卫生死角与垃圾清运不及时专项整治', 'PUBLIC', '公共服务', '卢龙县', '130324', '卢龙县',
 '卢龙县卢龙镇背街小巷存在多处卫生死角，杂物堆积、生活垃圾清运不及时堆积发臭，夏季高温加剧异味，影响市容环境卫生与周边居民生活，环卫诉求集中上升。',
 '1. 环卫部门开展为期两周的卫生死角集中清理，建立日产日清台账；2. 加密清运频次并由网格员每日巡查打卡；3. 设置定点收集容器并公示投诉直通电话；4. 对沿街商户落实门前三包责任制。',
 '两周内清理卫生死角37处，垃圾清运及时率提升至100%，环卫类投诉下降68%，居民回访满意率91%。',
 'MANUAL', 'DISABLED');