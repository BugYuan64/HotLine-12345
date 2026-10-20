package org.tjetc.backend.common;

/**
 * 业务异常：携带文档定义错误码，由 GlobalExceptionHandler 统一转换为统一响应结构
 */
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public BusinessException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage());
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}
