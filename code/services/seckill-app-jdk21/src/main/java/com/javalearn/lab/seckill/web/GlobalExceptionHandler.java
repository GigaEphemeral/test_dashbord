package com.javalearn.lab.seckill.web;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.ErrorCode;
import com.javalearn.lab.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps business errors to the {@link Result} envelope and HTTP status codes.
 * Unexpected exceptions are logged with a stack trace but never leaked to the
 * client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        HttpStatus status = httpStatus(errorCode);
        if (status.is5xxServerError()) {
            log.error("Business exception mapped to {} ({})", status.value(), errorCode.code(), exception);
        } else {
            log.info("Business exception {}: {}", errorCode.code(), exception.getMessage());
        }
        return ResponseEntity.status(status)
                .body(Result.failure(errorCode, exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String message = "Invalid value for parameter '" + exception.getName()
                + "': " + exception.getValue();
        return ResponseEntity.badRequest()
                .body(Result.failure(CommonErrorCode.INVALID_ARGUMENT, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(CommonErrorCode.INTERNAL_ERROR));
    }

    private static HttpStatus httpStatus(ErrorCode errorCode) {
        if (errorCode == CommonErrorCode.INVALID_ARGUMENT) {
            return HttpStatus.BAD_REQUEST;
        }
        if (errorCode == CommonErrorCode.UNAUTHORIZED) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (errorCode == CommonErrorCode.NOT_FOUND) {
            return HttpStatus.NOT_FOUND;
        }
        if (errorCode == CommonErrorCode.CONFLICT) {
            return HttpStatus.CONFLICT;
        }
        if (errorCode == CommonErrorCode.RATE_LIMITED) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (errorCode == CommonErrorCode.SERVICE_UNAVAILABLE) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
