SET NAMES utf8mb4;
-- ============================================================================
-- 系统审计日志清理（仅测试环境使用）
--
-- ★★★ 警告：三端审计日志（admin_audit_log / dept_audit_log / street_audit_log）
--   生产环境为 append-only（仅 INSERT/SELECT，禁止 UPDATE/DELETE，永久留存）。
--   本文件仅供测试环境清空测试数据用，严禁在任何生产/演示环境执行。
--
-- 执行顺序：先执行 23-test-appeals.sql（清理诉求+工单），再执行本文件。
--   原因：appeal_child.clean_log_id 引用 admin_audit_log.id，
--   若先清审计而子诉求仍在，会产生悬空引用；23 号文件已删除全部子诉求。
--
-- 本文件可重复执行（幂等）
-- ============================================================================

-- 1. 清空三端审计日志（LOGIN/OPERATION/ERROR/AI_GENERATION 一并清除）
DELETE FROM admin_audit_log;
DELETE FROM dept_audit_log;
DELETE FROM street_audit_log;

-- ---------------------------------------------------------------------------
-- （可选）只清理测试流程产生的审计记录，保留登录/人工操作日志：
--   测试流程写入特征：operator='AI-SERVICE'（拆分/分类/预判/预警 AI 审计）
--   或 summary 含测试诉求编号前缀（AP202608311000%）
-- 使用时注释掉上面的全量 DELETE，放开下面六条：
-- DELETE FROM admin_audit_log WHERE operator = 'AI-SERVICE';
-- DELETE FROM admin_audit_log WHERE summary LIKE '%AP202608311000%';
-- DELETE FROM dept_audit_log WHERE operator = 'AI-SERVICE';
-- DELETE FROM dept_audit_log WHERE summary LIKE '%AP202608311000%';
-- DELETE FROM street_audit_log WHERE operator = 'AI-SERVICE';
-- DELETE FROM street_audit_log WHERE summary LIKE '%AP202608311000%';
-- ---------------------------------------------------------------------------

-- 2. 验证：三端审计日志表应为空（count = 0）
SELECT 'admin_audit_log'  AS tbl, COUNT(*) AS remaining FROM admin_audit_log
UNION ALL SELECT 'dept_audit_log',   COUNT(*) FROM dept_audit_log
UNION ALL SELECT 'street_audit_log', COUNT(*) FROM street_audit_log;
