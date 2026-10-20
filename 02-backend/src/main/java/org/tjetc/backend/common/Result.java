package org.tjetc.backend.common;

import java.util.Map;

/**
 * 统一响应结构（API 接口文档 2.3）：
 * { code, message, data, timestamp }
 */
public record Result<T>(int code, String message, T data, long timestamp) {

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data,
                System.currentTimeMillis() / 1000);
    }

    public static <T> Result<T> fail(ResultCode code, String message) {
        return new Result<>(code.getCode(), message, null, System.currentTimeMillis() / 1000);
    }

    /**
     * 分页响应（API 接口文档 2.3 分页响应）：
     * data = { list, total, page, page_size, total_pages }
     */
    public static Result<Map<String, Object>> page(PageData<?> page) {
        return ok(page.toMap());
    }
}
