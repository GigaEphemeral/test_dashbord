package com.javalearn.lab.chaos.web;

import com.javalearn.lab.chaos.error.ChaosConflictException;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.error.InvalidChaosActionException;
import com.javalearn.lab.chaos.error.InvalidTailException;
import com.javalearn.lab.chaos.error.LabErrorCode;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class LabExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LabExceptionHandler.class);

    @ExceptionHandler(ContainerNotFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(ContainerNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(CommonErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(ContainerNotAllowedException.class)
    public ResponseEntity<Result<Void>> handleNotAllowed(ContainerNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.failure(LabErrorCode.FORBIDDEN, ex.getMessage()));
    }

    @ExceptionHandler(InvalidTailException.class)
    public ResponseEntity<Result<Void>> handleInvalidTail(InvalidTailException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(CommonErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    @ExceptionHandler(InvalidChaosActionException.class)
    public ResponseEntity<Result<Void>> handleInvalidAction(InvalidChaosActionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(CommonErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    @ExceptionHandler(ChaosConflictException.class)
    public ResponseEntity<Result<Void>> handleChaosConflict(ChaosConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.failure(CommonErrorCode.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(CommonErrorCode.INVALID_ARGUMENT, "Invalid request body"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(CommonErrorCode.INVALID_ARGUMENT,
                        "Invalid value for parameter '" + ex.getName() + "'"));
    }

    @ExceptionHandler(DockerUnavailableException.class)
    public ResponseEntity<Result<Void>> handleDockerUnavailable(DockerUnavailableException ex) {
        log.warn("Docker operation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.failure(CommonErrorCode.SERVICE_UNAVAILABLE, ex.getMessage()));
    }
}
