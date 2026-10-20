package org.tjetc.backend.service.admin;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.util.ExcelUtil;
import org.tjetc.backend.dto.admin.ClassifyCategoryRequest;
import org.tjetc.backend.dto.admin.ClassifyKeywordRequest;
import org.tjetc.backend.dto.admin.ClassifyRuleRequest;
import org.tjetc.backend.dto.admin.RagSampleRequest;
import org.tjetc.backend.dto.ai.AiClassifyResult;
import org.tjetc.backend.dto.ai.AiSimilarSample;
import org.tjetc.backend.dto.admin.ClassifyPrimaryRequest;
import org.tjetc.backend.entity.admin.ClassifyCategory;
import org.tjetc.backend.entity.admin.ClassifyKeyword;
import org.tjetc.backend.entity.admin.ClassifyPrimary;
import org.tjetc.backend.entity.admin.ClassifyRule;
import org.tjetc.backend.entity.admin.RagSample;
import org.tjetc.backend.mapper.admin.ClassifyCategoryMapper;
import org.tjetc.backend.mapper.admin.ClassifyKeywordMapper;
import org.tjetc.backend.mapper.admin.ClassifyPrimaryMapper;
import org.tjetc.backend.mapper.admin.ClassifyRuleMapper;
import org.tjetc.backend.mapper.admin.RagSampleMapper;
import org.tjetc.backend.mapper.admin.AdminAuditLogMapper;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;
import org.tjetc.backend.service.common.AiServiceClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 分类规则维护（需求 10 分类规则维护 / API 7.1~7.7）：
 * - 关键词/规则/样本 CRUD，变更后同步 Redis 分类知识桥（cls:keywords/cls:rules/cls:labels、rag:pending），
 *   Python AI 服务只读该桥（架构隔离，交接文档 §5）
 * - 分类效果测试转发 /ai/v1/classify（AI 不可用降级返回，不抛 500）
 * - 规则与样本 Excel 批量导入导出；分类准确率统计
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassifyService {

    // ---- Redis 分类知识桥键名（与 03-fastAPI/app/config.py 完全一致，禁止修改）----
    /** 关键词库：HASH field=关键词 value={"category","weight","status"} */
    public static final String KEY_CLS_KEYWORDS = "cls:keywords";
    /** 分类规则：HASH field=一级分类 value={"hit_count_threshold","weight_sum_threshold"} */
    public static final String KEY_CLS_RULES = "cls:rules";
    /** 二级标签：HASH field=一级分类 value="标签1,标签2" */
    public static final String KEY_CLS_LABELS = "cls:labels";
    /** 一级分类：HASH field=分类代码 value={"name","department","status"}（Python 读此键取分类名称与部门） */
    public static final String KEY_CLS_PRIMARY = "cls:primary";
    /** 待向量化样本队列：LIST 元素 {"sample_id","category","content"} */
    public static final String KEY_RAG_PENDING = "rag:pending";
    /** RAG 向量键前缀（与 rag_retriever.py 一致）：rag:v:{sample_id} */
    public static final String RAG_VECTOR_KEY_PREFIX = "rag:v:";
    /** 人工分类修正审计模块标识（P1 工单分类人工修正 API 6.9 写入，准确率统计复用） */
    /** 人工修正率统计口径：与实际写入的审计 module 一致（order-classify-correct） */
    public static final String MODULE_CLASSIFY_CORRECT = "order-classify-correct";

    /** 样本来源枚举（schema.sql rag_sample.source / API 7.3） */
    private static final Set<String> SAMPLE_SOURCES = Set.of("MANUAL_REVIEW", "EXPERT", "SATISFIED", "CORRECTED");
    /** 质量等级枚举（API 14.1） */
    private static final Set<String> QUALITY_LEVELS = Set.of("P0", "P1", "P2", "P3");
    /** 导入类型（API 7.5） */
    private static final Set<String> IMPORT_TYPES = Set.of("KEYWORD", "RULE", "SAMPLE");

    private final ClassifyPrimaryMapper primaryMapper;
    private final ClassifyCategoryMapper categoryMapper;
    private final ClassifyKeywordMapper keywordMapper;
    private final ClassifyRuleMapper ruleMapper;
    private final RagSampleMapper sampleMapper;
    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final AdminAuditLogMapper auditLogMapper;
    private final AiServiceClient aiServiceClient;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------ 分类体系（需求 10.3(1)）

    /** 分类体系查询：三大一级分类 + 二级标签（前端分类树数据源） */
    public List<Map<String, Object>> categories() {
        // 执行categories操作
        return categoryMapper.selectAll().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("primary_category", c.getPrimaryCategory());
            m.put("sub_label", c.getSubLabel());
            m.put("sort_order", c.getSortOrder());
            return m;
        }).toList();
    }

    /** 新增二级分类标签（需求 10.3(1)），变更后同步 cls:labels 知识桥 */
    public Map<String, Object> addCategory(ClassifyCategoryRequest request) {
        // 添加Category
        if (primaryMapper.selectByCode(request.primaryCategory()) == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "一级分类不存在：" + request.primaryCategory());
        }
        if (categoryMapper.selectByPrimaryAndLabel(request.primaryCategory(), request.subLabel()) != null) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该分类下已存在相同二级标签");
        }
        ClassifyCategory category = new ClassifyCategory();
        category.setPrimaryCategory(request.primaryCategory());
        category.setSubLabel(request.subLabel());
        // 排序：同分类内追加到末尾
        int maxOrder = categoryMapper.selectAll().stream()
                .filter(c -> c.getPrimaryCategory().equals(request.primaryCategory()))
                .mapToInt(c -> c.getSortOrder() == null ? 0 : c.getSortOrder())
                .max().orElse(0);
        category.setSortOrder(maxOrder + 1);
        categoryMapper.insert(category);
        syncLabelBridge();
        auditLogService.recordOperation("ADMIN", "classify-category",
                "新增二级分类标签：" + request.primaryCategory() + "/" + request.subLabel());
        return Map.of("category_id", category.getId());
    }

    // ------------------------------------------------------------ 一级分类维护（需求 10.3(1)）

    /** 一级分类列表（含名称/部门，前端分类下拉数据源） */
    public List<Map<String, Object>> listPrimaries() {
        return primaryMapper.selectAll().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("department", p.getDepartment());
            m.put("sort_order", p.getSortOrder());
            m.put("status", p.getStatus());
            return m;
        }).toList();
    }

    /** 新增一级分类，变更后同步 cls:primary 知识桥 */
    public Map<String, Object> addPrimary(ClassifyPrimaryRequest request) {
        if (primaryMapper.selectByCode(request.code().trim()) != null) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该分类代码已存在");
        }
        ClassifyPrimary primary = new ClassifyPrimary();
        primary.setCode(request.code().trim());
        primary.setName(request.name().trim());
        primary.setDepartment(request.department().trim());
        int maxOrder = primaryMapper.selectAll().stream()
                .mapToInt(p -> p.getSortOrder() == null ? 0 : p.getSortOrder()).max().orElse(0);
        primary.setSortOrder(request.sortOrder() == null ? maxOrder + 1 : request.sortOrder());
        primary.setStatus(request.status() == null ? "ENABLED" : request.status());
        primaryMapper.insert(primary);
        syncPrimaryBridge();
        auditLogService.recordOperation("ADMIN", "classify-primary",
                "新增一级分类：" + primary.getCode() + "(" + primary.getName() + ")");
        return Map.of("id", primary.getId());
    }

    /** 编辑一级分类（名称/部门/排序/状态），变更后同步 cls:primary 知识桥 */
    public Map<String, Object> updatePrimary(Long id, ClassifyPrimaryRequest request) {
        ClassifyPrimary primary = primaryMapper.selectById(id);
        if (primary == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "一级分类不存在");
        }
        if (request.name() != null && !request.name().isBlank()) {
            primary.setName(request.name().trim());
        }
        if (request.department() != null && !request.department().isBlank()) {
            primary.setDepartment(request.department().trim());
        }
        if (request.sortOrder() != null) {
            primary.setSortOrder(request.sortOrder());
        }
        if (request.status() != null) {
            primary.setStatus(request.status());
        }
        primaryMapper.update(primary);
        syncPrimaryBridge();
        auditLogService.recordOperation("ADMIN", "classify-primary",
                "编辑一级分类：" + primary.getCode() + "(" + primary.getName() + ")");
        return Map.of("id", id);
    }

    /** 删除一级分类（下存在二级标签时禁止删除） */
    public Map<String, Object> deletePrimary(Long id) {
        ClassifyPrimary primary = primaryMapper.selectById(id);
        if (primary == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "一级分类不存在");
        }
        boolean hasSub = categoryMapper.selectAll().stream()
                .anyMatch(c -> c.getPrimaryCategory().equals(primary.getCode()));
        if (hasSub) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该一级分类下仍有二级标签，请先删除二级标签");
        }
        primaryMapper.delete(id);
        syncPrimaryBridge();
        auditLogService.recordOperation("ADMIN", "classify-primary", "删除一级分类：" + primary.getCode());
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------ 关键词维护（API 7.1）

    public PageData<Map<String, Object>> pageKeywords(Long categoryId, String keyword, String status,
                                                      int page, int pageSize) {
        // 执行pageKeywords操作
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> rows = keywordMapper.selectPage(categoryId, keyword, status, offset, pageSize);
        long total = keywordMapper.countPage(categoryId, keyword, status);
        // 显式转换 snake_case 键名（MyBatis 驼峰映射不影响返回结构，与 API 文档风格一致）
        List<Map<String, Object>> list = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("category_id", v(r, "categoryId", "category_id"));
            m.put("keyword", r.get("keyword"));
            m.put("weight", r.get("weight"));
            m.put("status", r.get("status"));
            m.put("primary_category", v(r, "primaryCategory", "primary_category"));
            m.put("sub_label", v(r, "subLabel", "sub_label"));
            return m;
        }).toList();
        return PageData.of(list, total, page, pageSize);
    }

    public Map<String, Object> addKeyword(ClassifyKeywordRequest request) {
        // 添加Keyword
        requireCategory(request.categoryId());
        if (keywordMapper.countByCategoryAndKeyword(request.categoryId(), request.keyword()) > 0) {
            throw new BusinessException(ResultCode.DATA_EXISTS, "该分类下已存在相同关键词");
        }
        ClassifyKeyword keyword = new ClassifyKeyword();
        keyword.setCategoryId(request.categoryId());
        keyword.setKeyword(request.keyword());
        keyword.setWeight(BigDecimal.valueOf(request.weight() == null ? 1.0 : request.weight()));
        keyword.setStatus(request.status() == null ? "ENABLED" : request.status());
        keywordMapper.insert(keyword);
        syncKeywordBridge();
        auditLogService.recordOperation("ADMIN", "classify-keyword",
                "新增关键词：" + request.keyword() + "（权重" + keyword.getWeight() + "）");
        return Map.of("keyword_id", keyword.getId());
    }

    public Map<String, Object> updateKeyword(Long id, ClassifyKeywordRequest request) {
        // 更新Keyword
        ClassifyKeyword keyword = keywordMapper.selectById(id);
        if (keyword == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "关键词不存在");
        }
        requireCategory(request.categoryId());
        keyword.setCategoryId(request.categoryId());
        keyword.setKeyword(request.keyword());
        if (request.weight() != null) {
            keyword.setWeight(BigDecimal.valueOf(request.weight()));
        }
        if (request.status() != null) {
            keyword.setStatus(request.status());
        }
        keywordMapper.update(keyword);
        syncKeywordBridge();
        return Map.of("keyword_id", id);
    }

    public Map<String, Object> deleteKeyword(Long id) {
        // 删除Keyword
        if (keywordMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "关键词不存在");
        }
        keywordMapper.delete(id);
        syncKeywordBridge();
        auditLogService.recordOperation("ADMIN", "classify-keyword", "删除关键词 id=" + id);
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------ 分类规则配置（API 7.2）

    public List<Map<String, Object>> rules() {
        // 执行rules操作
        return ruleMapper.selectAllWithCategory().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("category_id", v(r, "categoryId", "category_id"));
            m.put("hit_count_threshold", v(r, "hitCountThreshold", "hit_count_threshold"));
            m.put("weight_sum_threshold", v(r, "weightSumThreshold", "weight_sum_threshold"));
            m.put("primary_category", v(r, "primaryCategory", "primary_category"));
            return m;
        }).toList();
    }

    /** 规则配置：每个一级分类唯一，已存在则更新（阈值调整后即时同步 cls:rules 生效） */
    public Map<String, Object> saveRule(ClassifyRuleRequest request) {
        // 保存Rule
        requireCategory(request.categoryId());
        ClassifyRule rule = ruleMapper.selectByCategoryId(request.categoryId());
        if (rule == null) {
            rule = new ClassifyRule();
            rule.setCategoryId(request.categoryId());
            rule.setHitCountThreshold(request.hitCountThreshold() == null ? 1 : request.hitCountThreshold());
            rule.setWeightSumThreshold(BigDecimal.valueOf(
                    request.weightSumThreshold() == null ? 1.0 : request.weightSumThreshold()));
            ruleMapper.insert(rule);
        } else {
            if (request.hitCountThreshold() != null) {
                rule.setHitCountThreshold(request.hitCountThreshold());
            }
            if (request.weightSumThreshold() != null) {
                rule.setWeightSumThreshold(BigDecimal.valueOf(request.weightSumThreshold()));
            }
            ruleMapper.update(rule);
        }
        syncRuleBridge();
        auditLogService.recordOperation("ADMIN", "classify-rule",
                "配置分类规则 categoryId=" + request.categoryId()
                        + "（命中数阈值" + rule.getHitCountThreshold()
                        + "/权重和阈值" + rule.getWeightSumThreshold() + "）");
        return Map.of("rule_id", rule.getId());
    }

    // ------------------------------------------------------------ RAG 样本维护（API 7.3）

    public PageData<Map<String, Object>> pageSamples(Long categoryId, String source, String qualityLevel,
                                                     String status, int page, int pageSize) {
        // 执行pageSamples操作
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> rows =
                sampleMapper.selectPage(categoryId, source, qualityLevel, status, offset, pageSize);
        long total = sampleMapper.countPage(categoryId, source, qualityLevel, status);
        // 显式转换 snake_case 键名（与 API 文档风格一致）
        List<Map<String, Object>> list = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("category_id", v(r, "categoryId", "category_id"));
            m.put("content", r.get("content"));
            m.put("vector_id", v(r, "vectorId", "vector_id"));
            m.put("source", r.get("source"));
            m.put("quality_level", v(r, "qualityLevel", "quality_level"));
            m.put("original_error_category", v(r, "originalErrorCategory", "original_error_category"));
            m.put("status", r.get("status"));
            m.put("create_time", v(r, "createTime", "create_time"));
            m.put("primary_category", v(r, "primaryCategory", "primary_category"));
            m.put("sub_label", v(r, "subLabel", "sub_label"));
            return m;
        }).toList();
        return PageData.of(list, total, page, pageSize);
    }

    public Map<String, Object> addSample(RagSampleRequest request) {
        // 添加Sample
        requireCategory(request.categoryId());
        RagSample sample = new RagSample();
        sample.setCategoryId(request.categoryId());
        sample.setContent(request.content());
        sample.setSource(request.source());
        sample.setQualityLevel(request.qualityLevel());
        sample.setOriginalErrorCategory(request.originalErrorCategory());
        sample.setStatus(request.status() == null ? "ENABLED" : request.status());
        sampleMapper.insert(sample);
        if ("ENABLED".equals(sample.getStatus())) {
            pushRagPending(sample);
        }
        auditLogService.recordOperation("ADMIN", "classify-sample",
                "新增RAG样本 id=" + sample.getId() + "（来源" + sample.getSource()
                        + "/质量等级" + sample.getQualityLevel() + "）");
        return Map.of("sample_id", sample.getId());
    }

    public Map<String, Object> updateSample(Long id, RagSampleRequest request) {
        // 更新Sample
        RagSample sample = sampleMapper.selectById(id);
        if (sample == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "样本不存在");
        }
        requireCategory(request.categoryId());
        boolean wasEnabled = "ENABLED".equals(sample.getStatus());
        String oldContent = sample.getContent();
        sample.setCategoryId(request.categoryId());
        sample.setContent(request.content());
        sample.setSource(request.source());
        sample.setQualityLevel(request.qualityLevel());
        sample.setOriginalErrorCategory(request.originalErrorCategory());
        if (request.status() != null) {
            sample.setStatus(request.status());
        }
        sampleMapper.update(sample);
        boolean enabled = "ENABLED".equals(sample.getStatus());
        if (!enabled) {
            // 停用样本：从向量索引移除（键名与 rag_retriever.py 一致，FT 索引自动同步删除）
            removeSampleVector(id);
        } else if (!wasEnabled || !oldContent.equals(sample.getContent())) {
            // 重新启用或内容变更：重新推送向量化（新向量覆盖旧向量）
            pushRagPending(sample);
        }
        return Map.of("sample_id", id);
    }

    public Map<String, Object> deleteSample(Long id) {
        // 删除Sample
        if (sampleMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "样本不存在");
        }
        sampleMapper.delete(id);
        removeSampleVector(id);
        auditLogService.recordOperation("ADMIN", "classify-sample", "删除RAG样本 id=" + id);
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------ 分类效果测试（API 7.4）

    /**
     * 分类效果测试：转发 /ai/v1/classify（关键词→RAG→大模型混合策略）
     * refresh=true 强制 Python 侧刷新知识桥缓存，保证刚改的关键词/规则立即命中
     * AI 服务不可用时按「AI 降级特殊响应」返回 200 + ai_degraded，禁止抛 500
     */
    public Map<String, Object> test(String text) {
        // 测试操作（refresh=true：管理端在线测试要求改完即测即命中，不走 60s 缓存）
        Optional<AiClassifyResult> opt = aiServiceClient.classify(null, text, true);
        Map<String, Object> data = new LinkedHashMap<>();
        if (opt.isEmpty()) {
            data.put("category", null);
            data.put("confidence", null);
            data.put("hit_keywords", List.of());
            data.put("similar_samples", List.of());
            data.put("score", null);
            data.put("need_review", true);
            data.put("ai_degraded", true);
            data.put("fallback_message", "AI分类服务暂时不可用，请稍后重试或检查服务状态");
            return data;
        }
        AiClassifyResult r = opt.get();
        List<String> hits = r.hitKeywords() == null ? List.of() : r.hitKeywords();
        Double score = keywordScore(hits);
        if (score == null) {
            score = r.confidence();
        }
        List<Map<String, Object>> samples = (r.similarSamples() == null ? List.<AiSimilarSample>of() : r.similarSamples())
                .stream().map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sample_id", s.sampleId());
                    m.put("similarity", s.similarity());
                    return m;
                }).toList();
        data.put("category", r.primaryCategory());
        data.put("confidence", r.confidence());
        data.put("hit_keywords", hits);
        data.put("similar_samples", samples);
        data.put("score", score);
        data.put("need_review", Boolean.TRUE.equals(r.needReview()));
        data.put("ai_degraded", false);
        // AI 生成内容审计留痕（API 2.5：显式标识 + 决策依据）
        auditLogService.recordAiGeneration("CLASSIFY_TEST",
                "AI生成·分类效果测试：" + (r.primaryCategory() == null ? "未判定" : r.primaryCategory()),
                r.confidence(),
                hits.isEmpty() ? "无关键词命中" : "命中关键词[" + String.join(",", hits) + "]",
                "qwen-plus");
        return data;
    }

    /** 匹配得分（API 7.4 score）：命中关键词权重求和；无命中时回退为置信度 */
    private Double keywordScore(List<String> hits) {
        // 执行keywordScore操作
        if (hits.isEmpty()) {
            return null;
        }
        List<ClassifyKeyword> kws = keywordMapper.selectWeightsByKeywords(hits);
        if (kws.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (ClassifyKeyword k : kws) {
            if (k.getWeight() != null) {
                sum += k.getWeight().doubleValue();
            }
        }
        return BigDecimal.valueOf(sum).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------ 批量导入导出（API 7.5/7.6）

    /**
     * Excel 批量导入（API 7.5）：KEYWORD/RULE/SAMPLE 三类模板
     * 单行校验失败跳过并计数，不影响其余行；导入完成后同步知识桥
     */
    public Map<String, Object> importExcel(MultipartFile file, String type) {
        // 导入Excel
        if (!IMPORT_TYPES.contains(type)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "导入类型必须为KEYWORD/RULE/SAMPLE之一");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "导入文件不能为空");
        }
        List<String[]> rows;
        try {
            rows = ExcelUtil.readRows(file.getInputStream());
        } catch (java.io.IOException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "导入文件读取失败：" + e.getMessage());
        }
        int imported = 0;
        int failed = 0;
        for (int i = 0; i < rows.size(); i++) {
            try {
                if (importRow(type, rows.get(i))) {
                    imported++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("分类导入失败 type={} 行{}：{}", type, i + 2, e.getMessage());
            }
        }
        // 导入完成后同步知识桥（关键词/规则即时生效，样本推送向量化队列）
        if (imported > 0) {
            switch (type) {
                case "KEYWORD" -> syncKeywordBridge();
                case "RULE" -> syncRuleBridge();
                case "SAMPLE" -> { /* 样本逐条已推送 rag:pending，无需全量同步 */ }
                default -> { }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("imported", imported);
        data.put("failed", failed);
        return data;
    }

    /** 解析并写入单行导入数据（模板列顺序见导出方法表头） */
    private boolean importRow(String type, String[] row) {
        // 导入Row
        switch (type) {
            case "KEYWORD" -> {
                if (row.length < 4 || row[0].isEmpty() || row[2].isEmpty()) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "一级分类/关键词不能为空");
                }
                ClassifyCategory category = requireCategoryByLabel(row[0], row.length > 1 ? row[1] : "");
                if (keywordMapper.countByCategoryAndKeyword(category.getId(), row[2]) > 0) {
                    throw new BusinessException(ResultCode.DATA_EXISTS, "关键词已存在：" + row[2]);
                }
                ClassifyKeyword keyword = new ClassifyKeyword();
                keyword.setCategoryId(category.getId());
                keyword.setKeyword(row[2]);
                keyword.setWeight(BigDecimal.valueOf(parseDouble(row[3], 1.0)));
                keyword.setStatus(row.length > 4 && !row[4].isEmpty() ? row[4] : "ENABLED");
                keywordMapper.insert(keyword);
            }
            case "RULE" -> {
                if (row.length < 3 || row[0].isEmpty()) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "一级分类不能为空");
                }
                ClassifyCategory category = firstCategoryOfPrimary(row[0]);
                ClassifyRule rule = ruleMapper.selectByCategoryId(category.getId());
                if (rule == null) {
                    rule = new ClassifyRule();
                    rule.setCategoryId(category.getId());
                    ruleMapper.insert(rule);
                }
                rule.setHitCountThreshold((int) parseDouble(row[1], 1.0));
                rule.setWeightSumThreshold(BigDecimal.valueOf(parseDouble(row[2], 1.0)));
                ruleMapper.update(rule);
            }
            case "SAMPLE" -> {
                if (row.length < 4 || row[0].isEmpty() || row[2].isEmpty()) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "一级分类/样本内容不能为空");
                }
                if (!SAMPLE_SOURCES.contains(row[3])) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "来源非法：" + row[3]);
                }
                if (!QUALITY_LEVELS.contains(row[4])) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "质量等级非法：" + row[4]);
                }
                ClassifyCategory category = requireCategoryByLabel(row[0], row[1]);
                RagSample sample = new RagSample();
                sample.setCategoryId(category.getId());
                sample.setContent(row[2]);
                sample.setSource(row[3]);
                sample.setQualityLevel(row[4]);
                sample.setStatus(row.length > 5 && !row[5].isEmpty() ? row[5] : "ENABLED");
                sampleMapper.insert(sample);
                if ("ENABLED".equals(sample.getStatus())) {
                    pushRagPending(sample);
                }
            }
            default -> throw new BusinessException(ResultCode.PARAM_ERROR, "未知导入类型：" + type);
        }
        return true;
    }

    /**
     * 导出 Excel（API 7.6）：构建关键词/规则/样本工作簿（由 Controller 流式回写，零落盘）
     */
    public XSSFWorkbook exportExcel(String type) {
        // 导出Excel
        if (!IMPORT_TYPES.contains(type)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "导出类型必须为KEYWORD/RULE/SAMPLE之一");
        }
        XSSFWorkbook workbook = ExcelUtil.createWorkbook();
        switch (type) {
            case "KEYWORD" -> exportKeywords(workbook);
            case "RULE" -> exportRules(workbook);
            case "SAMPLE" -> exportSamples(workbook);
            default -> { }
        }
        return workbook;
    }

    /** 关键词导出模板：一级分类 | 二级标签 | 关键词 | 权重 | 状态 */
    private void exportKeywords(XSSFWorkbook workbook) {
        // 导出Keywords
        Sheet sheet = workbook.createSheet("关键词");
        ExcelUtil.writeHeader(sheet, new String[]{"一级分类", "二级标签", "关键词", "权重", "状态"});
        List<Map<String, Object>> rows = keywordMapper.selectAllWithCategory();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            ExcelUtil.writeRow(sheet, i + 1, new Object[]{
                    v(r, "primaryCategory", "primary_category"), v(r, "subLabel", "sub_label"),
                    r.get("keyword"), r.get("weight"), r.get("status")});
        }
        ExcelUtil.autoSize(sheet, 5);
    }

    /** 规则导出模板：一级分类 | 命中数量阈值 | 权重和阈值 */
    private void exportRules(XSSFWorkbook workbook) {
        // 导出Rules
        Sheet sheet = workbook.createSheet("分类规则");
        ExcelUtil.writeHeader(sheet, new String[]{"一级分类", "命中数量阈值", "权重和阈值"});
        List<Map<String, Object>> rows = ruleMapper.selectAllWithCategory();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            ExcelUtil.writeRow(sheet, i + 1, new Object[]{
                    v(r, "primaryCategory", "primary_category"),
                    v(r, "hitCountThreshold", "hit_count_threshold"),
                    v(r, "weightSumThreshold", "weight_sum_threshold")});
        }
        ExcelUtil.autoSize(sheet, 3);
    }

    /** 样本导出模板：一级分类 | 二级标签 | 样本内容 | 来源 | 质量等级 | 状态 */
    private void exportSamples(XSSFWorkbook workbook) {
        // 导出Samples
        Sheet sheet = workbook.createSheet("知识库样本");
        ExcelUtil.writeHeader(sheet, new String[]{"一级分类", "二级标签", "样本内容", "来源", "质量等级", "状态"});
        List<RagSample> samples = sampleMapper.selectAll();
        Map<Long, ClassifyCategory> categoryMap = new LinkedHashMap<>();
        for (ClassifyCategory c : categoryMapper.selectAll()) {
            categoryMap.put(c.getId(), c);
        }
        for (int i = 0; i < samples.size(); i++) {
            RagSample s = samples.get(i);
            ClassifyCategory c = categoryMap.get(s.getCategoryId());
            ExcelUtil.writeRow(sheet, i + 1, new Object[]{
                    c == null ? "" : c.getPrimaryCategory(),
                    c == null ? "" : c.getSubLabel(),
                    s.getContent(), s.getSource(), s.getQualityLevel(), s.getStatus()});
        }
        ExcelUtil.autoSize(sheet, 6);
    }

    // ------------------------------------------------------------ 分类准确率统计（API 7.7）

    /**
     * 分类准确率统计（API 7.7）：
     * - 整体准确率 = 统计周期内已分类工单的平均 AI 置信度（work_order.category/confidence）
     * - 人工修正率 = 人工修正记录数（审计 module=classify-correct，P1 写入）/ 已分类工单总数
     * - 各分类准确率 = 按一级分类分组的平均置信度
     */
    public Map<String, Object> accuracy(String period, String category) {
        // 执行accuracy操作
        int days = parsePeriodDays(period);
        LocalDateTime start = LocalDateTime.now().minusDays(days);
        Double overall = adminWorkOrderMapper.overallAccuracy(start, category);
        long total = adminWorkOrderMapper.countClassified(start, category);
        long corrected = auditLogMapper.countOperationByModule(MODULE_CLASSIFY_CORRECT, start);
        List<Map<String, Object>> perCategory = adminWorkOrderMapper.accuracyByCategory(start, category);
        // 人工修正率口径：审计记录（audit_log）无分类字段，仅全部分类可精确统计。
        // 按分类筛选时分子（全量修正数）与分母（该分类工单数）口径错位会虚高，
        // 故按分类查看时返回 null，前端显示"-"并注明仅全部分类可统计，避免误导
        boolean categoryFiltered = category != null && !category.isBlank();
        Double manualRate = categoryFiltered
                ? null
                : (total == 0 ? 0.0 : Math.min(1.0, (double) corrected / total));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("overall_accuracy", overall == null ? 0 : round4(overall));
        data.put("manual_correct_rate", manualRate == null ? null : round4(manualRate));
        data.put("per_category", perCategory);
        return data;
    }

    /** 统计周期解析：近 7/30/90 天，默认 30 天（API 7.7 period 参数） */
    private int parsePeriodDays(String period) {
        // 解析PeriodDays
        if (period == null || period.isBlank()) {
            return 30;
        }
        try {
            int days = Integer.parseInt(period.trim());
            return days > 0 ? days : 30;
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    private double round4(double v) {
        // 执行round4操作
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------ Redis 知识桥同步（交接文档 §5）

    /** 同步关键词库：差异更新 cls:keywords（新增/修改写入，删除的关键词移除字段） */
    /**
     * 全量重建分类知识桥（cls:keywords/cls:rules/cls:labels/cls:primary）：
     * 数据库直插/重置后 Redis 桥为空，启动时与手动触发均调用，保证 classify 的类别候选完整。 */
    public void rebuildBridges() {
        try {
            syncKeywordBridge();
            syncRuleBridge();
            syncLabelBridge();
            syncPrimaryBridge();
            log.info("分类知识桥全量重建完成");
        } catch (Exception e) {
            log.warn("分类知识桥重建失败：{}", e.getMessage());
        }
    }

    private void syncKeywordBridge() {
        // 同步KeywordBridge
        try {
            List<Map<String, Object>> rows = keywordMapper.selectAllWithCategory();
            Map<String, String> newFields = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("category", v(row, "primaryCategory", "primary_category"));
                meta.put("weight", row.get("weight"));
                meta.put("status", row.get("status"));
                newFields.put(String.valueOf(row.get("keyword")), objectMapper.writeValueAsString(meta));
            }
            Map<Object, Object> old = redis.opsForHash().entries(KEY_CLS_KEYWORDS);
            for (Object k : old.keySet()) {
                if (!newFields.containsKey(String.valueOf(k))) {
                    redis.opsForHash().delete(KEY_CLS_KEYWORDS, k);
                }
            }
            if (!newFields.isEmpty()) {
                redis.opsForHash().putAll(KEY_CLS_KEYWORDS, newFields);
            }
            log.info("分类知识桥 cls:keywords 同步完成：{} 条", newFields.size());
        } catch (Exception e) {
            // Redis 异常不阻断业务（Python 侧有内置默认值兜底）
            log.warn("分类知识桥 cls:keywords 同步失败：{}", e.getMessage());
        }
    }

    /** 同步分类规则：差异更新 cls:rules */
    private void syncRuleBridge() {
        // 同步RuleBridge
        try {
            List<Map<String, Object>> rows = ruleMapper.selectAllWithCategory();
            Map<String, String> newFields = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("hit_count_threshold", v(row, "hitCountThreshold", "hit_count_threshold"));
                meta.put("weight_sum_threshold", v(row, "weightSumThreshold", "weight_sum_threshold"));
                newFields.put(String.valueOf(v(row, "primaryCategory", "primary_category")),
                        objectMapper.writeValueAsString(meta));
            }
            Map<Object, Object> old = redis.opsForHash().entries(KEY_CLS_RULES);
            for (Object k : old.keySet()) {
                if (!newFields.containsKey(String.valueOf(k))) {
                    redis.opsForHash().delete(KEY_CLS_RULES, k);
                }
            }
            if (!newFields.isEmpty()) {
                redis.opsForHash().putAll(KEY_CLS_RULES, newFields);
            }
            log.info("分类知识桥 cls:rules 同步完成：{} 条", newFields.size());
        } catch (Exception e) {
            log.warn("分类知识桥 cls:rules 同步失败：{}", e.getMessage());
        }
    }

    /** 同步二级标签：差异更新 cls:labels（每个一级分类一条，逗号分隔按排序拼接） */
    private void syncLabelBridge() {
        // 同步LabelBridge
        try {
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            for (ClassifyCategory c : categoryMapper.selectAll()) {
                if (c.getSubLabel() == null || c.getSubLabel().isEmpty()) {
                    continue;
                }
                grouped.computeIfAbsent(c.getPrimaryCategory(), k -> new ArrayList<>()).add(c.getSubLabel());
            }
            Map<String, String> newFields = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
                newFields.put(e.getKey(), String.join(",", e.getValue()));
            }
            Map<Object, Object> old = redis.opsForHash().entries(KEY_CLS_LABELS);
            for (Object k : old.keySet()) {
                if (!newFields.containsKey(String.valueOf(k))) {
                    redis.opsForHash().delete(KEY_CLS_LABELS, k);
                }
            }
            if (!newFields.isEmpty()) {
                redis.opsForHash().putAll(KEY_CLS_LABELS, newFields);
            }
            log.info("分类知识桥 cls:labels 同步完成：{} 个分类", newFields.size());
        } catch (Exception e) {
            log.warn("分类知识桥 cls:labels 同步失败：{}", e.getMessage());
        }
    }

    /** 同步一级分类：差异更新 cls:primary（code -> {name, department, status}） */
    private void syncPrimaryBridge() {
        // 同步PrimaryBridge
        try {
            Map<String, String> newFields = new LinkedHashMap<>();
            for (ClassifyPrimary p : primaryMapper.selectAll()) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("name", p.getName());
                meta.put("department", p.getDepartment());
                meta.put("status", p.getStatus());
                newFields.put(p.getCode(), objectMapper.writeValueAsString(meta));
            }
            Map<Object, Object> old = redis.opsForHash().entries(KEY_CLS_PRIMARY);
            for (Object k : old.keySet()) {
                if (!newFields.containsKey(String.valueOf(k))) {
                    redis.opsForHash().delete(KEY_CLS_PRIMARY, k);
                }
            }
            if (!newFields.isEmpty()) {
                redis.opsForHash().putAll(KEY_CLS_PRIMARY, newFields);
            }
            log.info("分类知识桥 cls:primary 同步完成：{} 个一级分类", newFields.size());
        } catch (Exception e) {
            log.warn("分类知识桥 cls:primary 同步失败：{}", e.getMessage());
        }
    }

    /** 启动时全量同步分类知识桥（关键词/规则/标签/一级分类），供启动 Runner 调用 */
    public void syncAll() {
        syncKeywordBridge();
        syncRuleBridge();
        syncLabelBridge();
        syncPrimaryBridge();
        log.info("分类知识桥全量同步完成");
    }

    /** 启动播种 RAG 向量库（部署初始化，供启动 Runner 调用）：
     * 种子样本（rag_sample 表 SQL 直插）仅在 CRUD 时推 rag:pending，全新部署的向量库为空，
     * RAG 分类检索无数据（分类退化为纯关键词规则）。
     * 仅当向量库内已有键数少于 DB 启用样本数时全量补推（Python 侧按 rag:v:{sample_id}
     * 覆盖写天然幂等；键数充足则跳过，避免每次重启重复 Embedding 耗时耗额度）。 */
    public void seedRagVectorsIfMissing() {
        try {
            List<RagSample> enabled = sampleMapper.selectAll().stream()
                    .filter(s -> "ENABLED".equals(s.getStatus()))
                    .toList();
            if (enabled.isEmpty()) {
                return;
            }
            long existing = 0;
            try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                    .match(RAG_VECTOR_KEY_PREFIX + "*").count(500).build())) {
                while (cursor.hasNext()) {
                    cursor.next();
                    existing++;
                }
            }
            if (existing >= enabled.size()) {
                log.info("RAG 向量库已有 {} 条样本（DB 启用 {} 条），跳过启动播种", existing, enabled.size());
                return;
            }
            for (RagSample sample : enabled) {
                pushRagPending(sample);
            }
            log.info("RAG 向量库启动播种完成：推送 {} 条样本至 rag:pending（原向量库 {} 条）",
                    enabled.size(), existing);
        } catch (Exception e) {
            log.warn("RAG 向量库启动播种失败：{}", e.getMessage());
        }
    }

    /** 样本推送待向量化队列 rag:pending（Python 后台线程 Embedding 后写入向量索引） */
    private void pushRagPending(RagSample sample) {
        // 推送RagPending
        try {
            ClassifyCategory category = categoryMapper.selectById(sample.getCategoryId());
            if (category == null) {
                log.warn("样本 {} 分类不存在，跳过向量化推送", sample.getId());
                return;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sample_id", sample.getId());
            meta.put("category", category.getPrimaryCategory());
            meta.put("content", sample.getContent());
            redis.opsForList().leftPush(KEY_RAG_PENDING, objectMapper.writeValueAsString(meta));
            log.info("样本 {} 已推送待向量化队列 rag:pending", sample.getId());
        } catch (Exception e) {
            log.warn("样本 {} 推送待向量化队列失败：{}", sample.getId(), e.getMessage());
        }
    }

    /** 删除向量索引中的样本（停用/删除样本时调用；键名与 rag_retriever.py 一致） */
    private void removeSampleVector(Long sampleId) {
        // 移除SampleVector
        try {
            redis.delete(RAG_VECTOR_KEY_PREFIX + sampleId);
        } catch (Exception e) {
            log.warn("样本 {} 向量删除失败：{}", sampleId, e.getMessage());
        }
    }

    // ------------------------------------------------------------ 内部校验工具

    private void requireCategory(Long categoryId) {
        // 执行requireCategory操作
        if (categoryMapper.selectById(categoryId) == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "分类不存在");
        }
    }

    /** 按一级分类+二级标签定位分类行（导入用） */
    private ClassifyCategory requireCategoryByLabel(String primary, String subLabel) {
        // 执行requireCategoryByLabel操作
        if (primaryMapper.selectByCode(primary) == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "一级分类非法：" + primary);
        }
        ClassifyCategory category = categoryMapper.selectByPrimaryAndLabel(primary, subLabel);
        if (category == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND,
                    "分类不存在：" + primary + (subLabel == null || subLabel.isEmpty() ? "" : "/" + subLabel));
        }
        return category;
    }

    /** 取一级分类的首个分类行（规则配置按一级分类唯一，与 schema.sql 种子约定一致） */
    private ClassifyCategory firstCategoryOfPrimary(String primary) {
        // 执行firstCategoryOfPrimary操作
        return categoryMapper.selectAll().stream()
                .filter(c -> c.getPrimaryCategory().equals(primary))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.DATA_NOT_FOUND, "分类不存在：" + primary));
    }

    private double parseDouble(String s, double defaultValue) {
        // 解析Double
        if (s == null || s.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "数值格式非法：" + s);
        }
    }

    /**
     * MyBatis map 结果键名双兼容读取：优先 camelCase，回退 snake_case
     * （mapUnderscoreToCamelCase 配置对 Map 结果键的转换行为不依赖，两种场景均能正确取值）
     */
    private Object v(Map<String, Object> m, String camel, String snake) {
        // 执行v操作
        if (m.containsKey(camel)) {
            return m.get(camel);
        }
        return m.get(snake);
    }
}