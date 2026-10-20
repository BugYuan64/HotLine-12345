package org.tjetc.backend.controller.admin;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.tjetc.backend.common.PageData;
import org.tjetc.backend.common.Result;
import org.tjetc.backend.dto.admin.ClassifyCategoryRequest;
import org.tjetc.backend.dto.admin.ClassifyKeywordRequest;
import org.tjetc.backend.dto.admin.ClassifyPrimaryRequest;
import org.tjetc.backend.dto.admin.ClassifyRuleRequest;
import org.tjetc.backend.dto.admin.ClassifyTestRequest;
import org.tjetc.backend.dto.admin.RagSampleRequest;
import org.tjetc.backend.service.admin.ClassifyService;
import org.tjetc.backend.util.ExcelUtil;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 管理端分类规则维护接口（API 7.1~7.7）：
 * 关键词维护 / 规则配置 / RAG 样本维护 / 效果测试 / 批量导入导出 / 准确率统计
 * 注：GET 列表与 PUT/DELETE 为文档 CRUD 语义补充（文档仅定义 POST 入口，需求 10.3 要求完整维护能力）
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/classify")
public class AdminClassifyController {

    private final ClassifyService classifyService;

    /** 分类体系查询（需求 10.3(1)，前端分类树数据源） */
    @GetMapping("/categories")
    public Result<List<Map<String, Object>>> categories() {
        // 执行categories操作
        return Result.ok(classifyService.categories());
    }

    /** 新增二级分类标签（需求 10.3(1)） */
    /** 手动重建分类知识桥（数据库直插/重置后 Redis 桥为空时使用） */
    @PostMapping("/rebuild-bridge")
    public Result<Map<String, Object>> rebuildBridge() {
        classifyService.rebuildBridges();
        return Result.ok(Map.of("result", "rebuilt"));
    }

    @PostMapping("/categories")
    public Result<Map<String, Object>> addCategory(@Valid @RequestBody ClassifyCategoryRequest request) {
        // 添加Category
        return Result.ok(classifyService.addCategory(request));
    }

    // ------------------------------------------------------------ 一级分类（需求 10.3(1)）

    /** 一级分类列表（含名称/部门） */
    @GetMapping("/primaries")
    public Result<List<Map<String, Object>>> primaries() {
        // 执行primaries操作
        return Result.ok(classifyService.listPrimaries());
    }

    /** 新增一级分类 */
    @PostMapping("/primaries")
    public Result<Map<String, Object>> addPrimary(@Valid @RequestBody ClassifyPrimaryRequest request) {
        // 添加Primary
        return Result.ok(classifyService.addPrimary(request));
    }

    /** 编辑一级分类 */
    @PutMapping("/primaries/{id}")
    public Result<Map<String, Object>> updatePrimary(@PathVariable Long id,
                                                     @Valid @RequestBody ClassifyPrimaryRequest request) {
        // 更新Primary
        return Result.ok(classifyService.updatePrimary(id, request));
    }

    /** 删除一级分类 */
    @DeleteMapping("/primaries/{id}")
    public Result<Map<String, Object>> deletePrimary(@PathVariable Long id) {
        // 删除Primary
        return Result.ok(classifyService.deletePrimary(id));
    }

    // ------------------------------------------------------------ 关键词（API 7.1）

    /** 关键词分页查询（CRUD 语义补充） */
    @GetMapping("/keywords")
    public Result<Map<String, Object>> keywords(@RequestParam(required = false) Long category_id,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 执行keywords操作
        PageData<Map<String, Object>> data =
                classifyService.pageKeywords(category_id, keyword, status, page, pageSize);
        return Result.page(data);
    }

    /** 新增关键词（API 7.1） */
    @PostMapping("/keywords")
    public Result<Map<String, Object>> addKeyword(@Valid @RequestBody ClassifyKeywordRequest request) {
        // 添加Keyword
        return Result.ok(classifyService.addKeyword(request));
    }

    /** 编辑关键词（需求 10.3(2)） */
    @PutMapping("/keywords/{id}")
    public Result<Map<String, Object>> updateKeyword(@PathVariable Long id,
                                                     @Valid @RequestBody ClassifyKeywordRequest request) {
        // 更新Keyword
        return Result.ok(classifyService.updateKeyword(id, request));
    }

    /** 删除关键词（需求 10.3(2)） */
    @DeleteMapping("/keywords/{id}")
    public Result<Map<String, Object>> deleteKeyword(@PathVariable Long id) {
        // 删除Keyword
        return Result.ok(classifyService.deleteKeyword(id));
    }

    // ------------------------------------------------------------ 规则（API 7.2）

    /** 规则列表查询（CRUD 语义补充） */
    @GetMapping("/rules")
    public Result<List<Map<String, Object>>> rules() {
        // 执行rules操作
        return Result.ok(classifyService.rules());
    }

    /** 规则配置：已存在则更新，不存在则新增（API 7.2） */
    @PostMapping("/rules")
    public Result<Map<String, Object>> saveRule(@Valid @RequestBody ClassifyRuleRequest request) {
        // 保存Rule
        return Result.ok(classifyService.saveRule(request));
    }

    // ------------------------------------------------------------ 样本（API 7.3）

    /** 样本分页查询（CRUD 语义补充） */
    @GetMapping("/samples")
    public Result<Map<String, Object>> samples(@RequestParam(required = false) Long category_id,
                                               @RequestParam(required = false) String source,
                                               @RequestParam(required = false) String quality_level,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        // 执行samples操作
        PageData<Map<String, Object>> data =
                classifyService.pageSamples(category_id, source, quality_level, status, page, pageSize);
        return Result.page(data);
    }

    /** 新增样本（API 7.3） */
    @PostMapping("/samples")
    public Result<Map<String, Object>> addSample(@Valid @RequestBody RagSampleRequest request) {
        // 添加Sample
        return Result.ok(classifyService.addSample(request));
    }

    /** 编辑/停用样本（API 7.3 PUT） */
    @PutMapping("/samples/{id}")
    public Result<Map<String, Object>> updateSample(@PathVariable Long id,
                                                    @Valid @RequestBody RagSampleRequest request) {
        // 更新Sample
        return Result.ok(classifyService.updateSample(id, request));
    }

    /** 删除样本（API 7.3 DELETE） */
    @DeleteMapping("/samples/{id}")
    public Result<Map<String, Object>> deleteSample(@PathVariable Long id) {
        // 删除Sample
        return Result.ok(classifyService.deleteSample(id));
    }

    // ------------------------------------------------------------ 效果测试（API 7.4）

    /** 分类效果测试（API 7.4）：实时查看分类结果/命中关键词/相似样本/匹配得分 */
    @PostMapping("/test")
    public Result<Map<String, Object>> test(@Valid @RequestBody ClassifyTestRequest request) {
        // 测试操作
        return Result.ok(classifyService.test(request.text()));
    }

    // ------------------------------------------------------------ 导入导出（API 7.5/7.6）

    /** 规则与样本 Excel 批量导入（API 7.5） */
    @PostMapping("/import")
    public Result<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file,
                                                   @RequestParam("type") String type) {
        // 导入Excel
        return Result.ok(classifyService.importExcel(file, type));
    }

    /** 规则与样本 Excel 导出（API 7.6）：流式回写工作簿（零落盘） */
    @GetMapping("/export")
    public void export(@RequestParam("type") String type, HttpServletResponse response) throws IOException {
        // 导出数据
        XSSFWorkbook workbook = classifyService.exportExcel(type);
        ExcelUtil.writeResponse(workbook, "classify_" + type.toLowerCase() + ".xlsx", response);
    }

    // ------------------------------------------------------------ 准确率统计（API 7.7）

    /** 分类准确率统计（API 7.7）：整体准确率/人工修正率/各分类准确率 */
    @GetMapping("/accuracy")
    public Result<Map<String, Object>> accuracy(@RequestParam(required = false) String period,
                                                @RequestParam(required = false) String category) {
        // 执行accuracy操作
        return Result.ok(classifyService.accuracy(period, category));
    }
}