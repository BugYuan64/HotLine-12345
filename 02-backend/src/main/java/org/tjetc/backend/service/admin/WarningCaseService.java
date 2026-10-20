package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.dto.admin.WarningCaseRequest;
import org.tjetc.backend.entity.admin.WarningCase;
import org.tjetc.backend.mapper.admin.WarningCaseMapper;
import org.tjetc.backend.util.ExcelUtil;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 优秀处置案例库维护（预警建议 RAG 检索来源）：
 * - 案例 CRUD（新增/编辑/删除/Excel 批量导入），人工维护高质量处置标杆
 * - 入库时把待向量化案例推入 Redis LIST(case:pending)，Python 后台线程 Embedding
 *   后写入独立向量索引 case_idx（与分类 rag_idx 完全隔离）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarningCaseService {

    /** 待向量化处置案例队列（与 case_retriever.py 一致）：LIST 元素 {"case_id","case_no","title","category","region","content","measure_plan","measure_result","search_text"} */
    public static final String KEY_CASE_PENDING = "case:pending";
    /** 案例向量键前缀（与 case_retriever.py 一致）：case:v:{case_id} */
    public static final String CASE_VECTOR_KEY_PREFIX = "case:v:";

    private final WarningCaseMapper caseMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public PageData<Map<String, Object>> page(String keyword, String category, String region,
                                              String status, int page, int pageSize) {
        // 分页查询
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> list = caseMapper.selectPage(keyword, category, region, status, offset, pageSize);
        long total = caseMapper.countPage(keyword, category, region, status);
        return PageData.of(list, total, page, pageSize);
    }

    public Map<String, Object> add(WarningCaseRequest request) {
        // 添加案例
        WarningCase c = toCase(new WarningCase(), request);
        c.setCaseNo(request.caseNo() == null || request.caseNo().isBlank() ? nextCaseNo() : request.caseNo());
        c.setSource("MANUAL");
        c.setStatus(request.status() == null ? "ENABLED" : request.status());
        caseMapper.insert(c);
        if ("ENABLED".equals(c.getStatus())) {
            pushCasePending(c);
        }
        auditLogService.recordOperation("ADMIN", "warning-case", "新增处置案例 " + c.getCaseNo());
        return Map.of("case_id", c.getId());
    }

    public Map<String, Object> update(Long id, WarningCaseRequest request) {
        // 更新案例
        WarningCase c = caseMapper.selectById(id);
        if (c == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "处置案例不存在");
        }
        boolean wasEnabled = "ENABLED".equals(c.getStatus());
        String oldSearchText = c.getTitle() + "。" + c.getContent();
        toCase(c, request);
        if (request.status() != null) {
            c.setStatus(request.status());
        }
        caseMapper.update(c);
        boolean enabled = "ENABLED".equals(c.getStatus());
        if (!enabled) {
            // 停用案例：从向量索引移除（键名与 case_retriever.py 一致）
            removeCaseVector(id);
        } else if (!wasEnabled || !oldSearchText.equals(c.getTitle() + "。" + c.getContent())) {
            // 重新启用或问题内容变更：重新推送向量化（新向量覆盖旧向量）
            pushCasePending(c);
        }
        auditLogService.recordOperation("ADMIN", "warning-case", "编辑处置案例 " + c.getCaseNo());
        return Map.of("case_id", id);
    }

    public Map<String, Object> delete(Long id) {
        // 删除案例
        if (caseMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.DATA_NOT_FOUND, "处置案例不存在");
        }
        caseMapper.delete(id);
        removeCaseVector(id);
        auditLogService.recordOperation("ADMIN", "warning-case", "删除处置案例 id=" + id);
        return Map.of("deleted", true);
    }

    /** Excel 批量导入（模板列顺序：案例编号/问题标题/一级分类/二级分类/辖区/诉求描述/处置方案/处置效果/状态） */
    public Map<String, Object> importExcel(MultipartFile file) {
        // 导入Excel
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
                if (importRow(rows.get(i))) {
                    imported++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("处置案例导入失败 行{}：{}", i + 2, e.getMessage());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("imported", imported);
        data.put("failed", failed);
        return data;
    }

    /** 解析并写入单行导入数据 */
    private boolean importRow(String[] row) {
        // 导入单行
        if (row.length < 7 || row[1].isEmpty() || row[5].isEmpty() || row[6].isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "问题标题/诉求描述/处置方案不能为空");
        }
        WarningCase c = new WarningCase();
        c.setCaseNo(row.length > 0 && !row[0].isEmpty() ? row[0] : nextCaseNo());
        c.setTitle(row[1]);
        c.setCategory(row.length > 2 ? row[2] : null);
        c.setSubCategory(row.length > 3 ? row[3] : null);
        c.setRegion(row.length > 4 ? row[4] : null);
        c.setContent(row[5]);
        c.setMeasurePlan(row[6]);
        c.setMeasureResult(row.length > 7 ? row[7] : null);
        c.setStatus(row.length > 8 && !row[8].isEmpty() ? row[8] : "ENABLED");
        c.setSource("MANUAL");
        caseMapper.insert(c);
        if ("ENABLED".equals(c.getStatus())) {
            pushCasePending(c);
        }
        return true;
    }

    /** 案例推送待向量化队列 case:pending（Python 后台线程 Embedding 后写入向量索引） */
    private void pushCasePending(WarningCase c) {
        // 推送CasePending
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("case_id", c.getId());
            meta.put("case_no", c.getCaseNo());
            meta.put("title", c.getTitle());
            meta.put("category", c.getCategory());
            meta.put("region", c.getRegion());
            meta.put("content", c.getContent());
            meta.put("measure_plan", c.getMeasurePlan());
            meta.put("measure_result", c.getMeasureResult());
            // 向量化的文本：问题侧 = 标题 + 诉求描述（检索 query 与之对齐）
            meta.put("search_text", c.getTitle() + "。" + c.getContent());
            redis.opsForList().leftPush(KEY_CASE_PENDING, objectMapper.writeValueAsString(meta));
            log.info("处置案例 {} 已推送待向量化队列 case:pending", c.getId());
        } catch (Exception e) {
            log.warn("处置案例 {} 推送待向量化队列失败：{}", c.getId(), e.getMessage());
        }
    }

    /** 删除向量索引中的案例（停用/删除案例时调用；键名与 case_retriever.py 一致） */
    private void removeCaseVector(Long id) {
        // 移除CaseVector
        try {
            redis.delete(CASE_VECTOR_KEY_PREFIX + id);
        } catch (Exception e) {
            log.warn("处置案例 {} 向量删除失败：{}", id, e.getMessage());
        }
    }

    private WarningCase toCase(WarningCase c, WarningCaseRequest request) {
        // 转换Case
        c.setTitle(request.title());
        c.setCategory(request.category());
        c.setSubCategory(request.subCategory());
        c.setRegion(request.region());
        c.setContent(request.content());
        c.setMeasurePlan(request.measurePlan());
        c.setMeasureResult(request.measureResult());
        return c;
    }

    /** 案例编号：WC + 日期 + 当日序号 */
    private String nextCaseNo() {
        // 生成下一个案例编号
        String prefix = "WC" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int seq = caseMapper.countByPrefix(prefix) + 1;
        return prefix + String.format("%03d", seq);
    }
}