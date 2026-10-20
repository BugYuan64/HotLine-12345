package org.tjetc.backend.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 全局异常处理：所有接口返回 {code, message, data, timestamp} 统一结构（API 接口文档 2.3）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 客户端断开（SSE 心跳/长连接写入时发现连接已断）：响应已不可用，静默忽略。
     * 返回 void 不写响应体，避免在 text/event-stream 上协商 JSON 失败（No converter）刷日志。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnected(AsyncRequestNotUsableException e) {
        log.warn("客户端连接已断开（SSE/异步请求）：{}", e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        // 处理Business
        ResultCode code = e.getResultCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Result.fail(code, e.getMessage()));
    }

    /**
     * 参数校验失败（@Valid / @Validated）→ 40001
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        // 处理Validation
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ResultCode.PARAM_ERROR.getMessage());
        return ResponseEntity.status(ResultCode.PARAM_ERROR.getHttpStatus())
                .body(Result.fail(ResultCode.PARAM_ERROR, "参数校验失败：" + message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException e) {
        // 处理IllegalArgument：原始异常仅入日志，避免向客户端泄露内部实现细节
        log.warn("非法参数异常", e);
        return ResponseEntity.status(ResultCode.PARAM_ERROR.getHttpStatus())
                .body(Result.fail(ResultCode.PARAM_ERROR, "参数校验失败"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        // 处理Other
        log.error("服务器内部错误", e);
        return ResponseEntity.status(ResultCode.SERVER_ERROR.getHttpStatus())
                .body(Result.fail(ResultCode.SERVER_ERROR, ResultCode.SERVER_ERROR.getMessage()));
    }
}
