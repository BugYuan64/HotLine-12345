package org.tjetc.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 工单分类人工修正（API 6.9）：更新分类标签并记录修正记录（原分类/修正后分类/原因）
 * 分类代码由 classify_primary 表动态校验（Controller/Service 层），不再硬编码枚举
 */
public record OrderClassifyCorrectRequest(
        @NotBlank(message = "修正后分类不能为空")
        String category,

        @Size(max = 500, message = "修正原因不能超过500字")
        String reason) {
}