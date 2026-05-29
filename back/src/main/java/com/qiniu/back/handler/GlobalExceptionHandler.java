package com.qiniu.back.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.qiniu.back.domain.ErrorCode;
import com.qiniu.back.domain.ResponseDTO;
import com.qiniu.back.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseDTO<Void> handleValidException(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? "参数校验失败" : fieldError.getDefaultMessage();
        log.warn("Validation failed: {}", message);
        return ResponseDTO.userErrorParam(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseDTO<Void> handleConstraintException(ConstraintViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMessage());
        return ResponseDTO.userErrorParam(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseDTO<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return ResponseDTO.userErrorParam(ex.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseDTO<Void> handleNotLoginException(NotLoginException ex) {
        log.warn("Not login: {}", ex.getMessage());
        return ResponseDTO.error(ErrorCode.UNAUTHORIZED, "未登录，请先登录");
    }

    @ExceptionHandler(NotPermissionException.class)
    public ResponseDTO<Void> handleNotPermissionException(NotPermissionException ex) {
        log.warn("No permission: {}", ex.getMessage());
        return ResponseDTO.error(ErrorCode.FORBIDDEN, "没有访问权限");
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseDTO<Void> handleBusinessException(BusinessException ex) {
        log.warn("Business exception: {}", ex.getMessage());
        return new ResponseDTO<>(ex.getCode(), ex.getLevel(), false, ex.getMsg());
    }

    @ExceptionHandler(Exception.class)
    public ResponseDTO<Void> handleException(Exception ex) {
        log.error("System exception: {}", ex.getMessage(), ex);
        return ResponseDTO.error(ErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage());
    }
}
