package org.tjetc.backend.dto.citizen;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 客服知识库文档入库请求（管理端维护）：转发 Python 服务向量化后写入 Redis Stack 向量库
 * scope：citizen/staff/street，决定写入哪个端独立知识库（一个端一个知识库）
 */
public record ChatDocRequest(
        @NotBlank(message = "文档标题不能为空")
        @Size(max = 100, message = "文档标题不能超过100字")
        String title,
        @NotBlank(message = "文档内容不能为空")
        @Size(max = 5000, message = "文档内容不能超过5000字")
        String content,
        @Size(max = 50, message = "分类不能超过50字")
        String category,
        @Size(max = 20, message = "知识库作用域不合法")
        String scope) {
}
