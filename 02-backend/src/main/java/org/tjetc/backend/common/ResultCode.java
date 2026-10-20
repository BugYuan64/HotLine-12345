package org.tjetc.backend.common;

import org.springframework.http.HttpStatus;

/**
 * 错误码严格使用 API 接口文档 2.4 定义：200 / 40001~50003，禁止新增
 */
public enum ResultCode {

    SUCCESS(200, "success", HttpStatus.OK),
    PARAM_ERROR(40001, "参数校验失败", HttpStatus.BAD_REQUEST),
    SMS_CODE_ERROR(40002, "验证码错误或已过期", HttpStatus.BAD_REQUEST),
    DATA_NOT_FOUND(40003, "数据不存在", HttpStatus.NOT_FOUND),
    DATA_EXISTS(40004, "数据已存在", HttpStatus.CONFLICT),
    UNAUTHORIZED(40101, "未登录 / Token 已过期", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID(40102, "Token 无效 / 已被注销", HttpStatus.UNAUTHORIZED),
    ACCOUNT_DISABLED(40103, "账号已被停用", HttpStatus.FORBIDDEN),
    ACCOUNT_NOT_APPROVED(40104, "账号未通过审核", HttpStatus.FORBIDDEN),
    ACCOUNT_LOCKED(40105, "登录失败次数超限，账号已锁定", HttpStatus.LOCKED),
    FORBIDDEN(40201, "权限不足", HttpStatus.FORBIDDEN),
    ADMIN_FORBIDDEN(40202, "管理员权限不足", HttpStatus.FORBIDDEN),
    REGION_FORBIDDEN(40203, "越权访问他人辖区/部门数据", HttpStatus.FORBIDDEN),
    SERVER_ERROR(50001, "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    AI_SERVICE_ERROR(50002, "AI 服务调用失败（已降级）", HttpStatus.INTERNAL_SERVER_ERROR),
    THIRD_PARTY_ERROR(50003, "第三方服务调用失败", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ResultCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
