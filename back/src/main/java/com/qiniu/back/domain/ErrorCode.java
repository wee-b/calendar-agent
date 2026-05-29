package com.qiniu.back.domain;

public enum ErrorCode {

    BUSINESS_ERROR(200,"NORMAL","业务异常"),
    BAD_REQUEST(400, "WARN", "请求参数错误"),
    UNAUTHORIZED(401, "WARN", "未授权"),
    FORBIDDEN(403, "WARN", "禁止访问"),
    NOT_FOUND(404, "WARN", "资源不存在"),
    TOO_MANY_REQUESTS(429, "WARN", "请求过于频繁，请稍后再试"),
    INTERNAL_SERVER_ERROR(500, "ERROR", "服务器内部错误");

    private final Integer code;
    private final String level;
    private final String msg;

    ErrorCode(Integer code, String level, String msg) {
        this.code = code;
        this.level = level;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public String getLevel() {
        return level;
    }

    public String getMsg() {
        return msg;
    }
}
