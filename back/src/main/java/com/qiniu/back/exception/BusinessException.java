package com.qiniu.back.exception;

import com.qiniu.back.domain.ErrorCode;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;
    private final String level;
    private final String msg;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
        this.level = errorCode.getLevel();
        this.msg = errorCode.getMsg();
    }

    public BusinessException(ErrorCode errorCode, String msg) {
        super(msg);
        this.code = errorCode.getCode();
        this.level = errorCode.getLevel();
        this.msg = msg;
    }

    public BusinessException(Integer code, String level, String msg) {
        super(msg);
        this.code = code;
        this.level = level;
        this.msg = msg;
    }

    public BusinessException(String message) {
        super(message);
        this.code = 500;
        this.level = "ERROR";
        this.msg = message;
    }
}
