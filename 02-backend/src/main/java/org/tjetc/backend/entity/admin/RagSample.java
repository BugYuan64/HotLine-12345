package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG知识库样本表 rag_sample（需求 表7/表9 / API 7.3；按来源质量分级入库）
 */
@Data
@NoArgsConstructor
public class RagSample {

    private Long id;
    /** 分类ID（classify_category.id） */
    private Long categoryId;
    /** 样本内容 */
    private String content;
    /** 向量ID（Redis Stack 向量库） */
    private String vectorId;
    /** 样本来源：MANUAL_REVIEW/EXPERT/SATISFIED/CORRECTED */
    private String source;
    /** 质量等级：P0/P1/P2/P3 */
    private String qualityLevel;
    /** 原AI错误类别（人工修正样本标记，P3纠错样本） */
    private String originalErrorCategory;
    /** 状态：ENABLED/DISABLED */
    private String status;
    /** 入库时间 */
    private LocalDateTime createTime;
}
