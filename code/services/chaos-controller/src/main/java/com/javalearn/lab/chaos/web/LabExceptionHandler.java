package com.javalearn.lab.chaos.web;

import com.javalearn.lab.chaos.error.AppPathNotAllowedException;
import com.javalearn.lab.chaos.error.AppProxyRequestTooLargeException;
import com.javalearn.lab.chaos.error.AppProxyResponseTooLargeException;
import com.javalearn.lab.chaos.error.AppResponseNotJsonException;
import com.javalearn.lab.chaos.error.AppUnreachableException;
import com.javalearn.lab.chaos.error.ChaosConflictException;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.error.InvalidAppProxyRequestException;
import com.javalearn.lab.chaos.error.InvalidChaosActionException;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.error.InvalidScenarioException;
import com.javalearn.lab.chaos.error.InvalidTailException;
import com.javalearn.lab.chaos.error.InvalidToxiproxyRequestException;
import com.javalearn.lab.chaos.error.LabErrorCode;
import com.javalearn.lab.chaos.error.LedgerConflictException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.error.ProbeTargetProtectedException;
import com.javalearn.lab.chaos.error.ScenarioNotFoundException;
import com.javalearn.lab.chaos.error.ToxiproxyConflictException;
import com.javalearn.lab.chaos.error.ToxiproxyNotFoundException;
import com.javalearn.lab.chaos.error.ToxiproxyUnavailableException;
import com.javalearn.lab.chaos.error.UnknownAppInstanceException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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

    /**
     * B0-08 slice B: the target is the MySQL cluster probe container, so the
     * destructive action is refused. 403 with its own code (40302) - the remedy
     * (move the probe first) is different from a plain allow-list refusal.
     */
    @ExceptionHandler(ProbeTargetProtectedException.class)
    public ResponseEntity<Result<Void>> handleProbeTargetProtected(ProbeTargetProtectedException ex) {
        log.warn("Chaos action refused by the probe isolation rule: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.failure(LabErrorCode.PROBE_TARGET_PROTECTED, ex.getMessage()));
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

    @ExceptionHandler(InvalidScenarioException.class)
    public ResponseEntity<Result<Void>> handleInvalidScenario(InvalidScenarioException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(CommonErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    @ExceptionHandler(ScenarioNotFoundException.class)
    public ResponseEntity<Result<Void>> handleScenarioNotFound(ScenarioNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(CommonErrorCode.NOT_FOUND, ex.getMessage()));
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

    @ExceptionHandler(InvalidToxiproxyRequestException.class)
    public ResponseEntity<Result<Void>> handleInvalidToxiproxy(InvalidToxiproxyRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(CommonErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    @ExceptionHandler(ToxiproxyNotFoundException.class)
    public ResponseEntity<Result<Void>> handleToxiproxyNotFound(ToxiproxyNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(CommonErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(ToxiproxyConflictException.class)
    public ResponseEntity<Result<Void>> handleToxiproxyConflict(ToxiproxyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.failure(CommonErrorCode.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(ToxiproxyUnavailableException.class)
    public ResponseEntity<Result<Void>> handleToxiproxyUnavailable(ToxiproxyUnavailableException ex) {
        log.warn("Toxiproxy operation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.failure(CommonErrorCode.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    // ---------------------------------------------------- thread-lab proxy

    @ExceptionHandler(UnknownAppInstanceException.class)
    public ResponseEntity<Result<Void>> handleUnknownAppInstance(UnknownAppInstanceException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(CommonErrorCode.NOT_FOUND, ex.getMessage()));
    }

    /** Deny-by-default: an off-list path is indistinguishable from a missing one. */
    @ExceptionHandler(AppPathNotAllowedException.class)
    public ResponseEntity<Result<Void>> handleAppPathNotAllowed(AppPathNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(CommonErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(InvalidAppProxyRequestException.class)
    public ResponseEntity<Result<Void>> handleInvalidAppProxyRequest(InvalidAppProxyRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(CommonErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    /** Request body over the proxy byte budget: 413, not 400 (the request is well-formed). */
    @ExceptionHandler(AppProxyRequestTooLargeException.class)
    public ResponseEntity<Result<Void>> handleAppProxyRequestTooLarge(AppProxyRequestTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.failure(LabErrorCode.PAYLOAD_TOO_LARGE, ex.getMessage()));
    }

    /** Upstream answer over the proxy byte budget: relay-refusing bad gateway (502/50202). */
    @ExceptionHandler(AppProxyResponseTooLargeException.class)
    public ResponseEntity<Result<Void>> handleAppProxyResponseTooLarge(AppProxyResponseTooLargeException ex) {
        log.warn("App proxy response exceeded the relay limit: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Result.failure(LabErrorCode.RESPONSE_TOO_LARGE, ex.getMessage()));
    }

    @ExceptionHandler(AppUnreachableException.class)
    public ResponseEntity<Result<Void>> handleAppUnreachable(AppUnreachableException ex) {
        log.warn("App proxy target unreachable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.failure(CommonErrorCode.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    @ExceptionHandler(AppResponseNotJsonException.class)
    public ResponseEntity<Result<Void>> handleAppResponseNotJson(AppResponseNotJsonException ex) {
        log.warn("App proxy upstream returned a non-JSON answer: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Result.failure(LabErrorCode.BAD_GATEWAY, ex.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.failure(LabErrorCode.METHOD_NOT_ALLOWED,
                        "Method '" + ex.getMethod() + "' is not allowed; the proxy forwards GET and POST only"));
    }

    // -------------------------------------------------------------- ledger

    /** Ledger DEGRADED/NO_DATA or a live query failure: 503 with its own code. */
    @ExceptionHandler(LedgerUnavailableException.class)
    public ResponseEntity<Result<Void>> handleLedgerUnavailable(LedgerUnavailableException ex) {
        log.warn("Ledger unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.failure(LabErrorCode.LEDGER_UNAVAILABLE, ex.getMessage()));
    }

    @ExceptionHandler(LedgerRunNotFoundException.class)
    public ResponseEntity<Result<Void>> handleLedgerRunNotFound(LedgerRunNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(CommonErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(InvalidLedgerRequestException.class)
    public ResponseEntity<Result<Void>> handleInvalidLedgerRequest(InvalidLedgerRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(CommonErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    @ExceptionHandler(LedgerConflictException.class)
    public ResponseEntity<Result<Void>> handleLedgerConflict(LedgerConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.failure(CommonErrorCode.CONFLICT, ex.getMessage()));
    }
}
