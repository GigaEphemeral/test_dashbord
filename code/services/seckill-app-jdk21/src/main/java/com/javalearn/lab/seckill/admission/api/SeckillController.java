package com.javalearn.lab.seckill.admission.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.admission.app.SeckillAdmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Async seckill admission API.
 *
 * <ul>
 *   <li>{@code POST /api/seckill/orders}: 202 in async mode, 200 with
 *       {@code status=CREATED} when the synchronous toggle is off.</li>
 *   <li>{@code GET /api/seckill/orders/{requestId}}: polling; 404 for unknown
 *       or expired request ids.</li>
 * </ul>
 *
 * <p>The header is declared optional so a missing value still produces the
 * {@link Result} envelope; the admission service rejects it with 400.</p>
 */
@RestController
@RequestMapping("/api/seckill/orders")
public class SeckillController {

    private final SeckillAdmissionService admissionService;

    public SeckillController(SeckillAdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @PostMapping
    public ResponseEntity<Result<SeckillResponse>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateSeckillOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT,
                    "Idempotency-Key header is required");
        }
        SeckillResponse response = admissionService.admit(idempotencyKey, request);
        HttpStatus status = admissionService.isAsyncEnabled()
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(Result.success(response));
    }

    @GetMapping("/{requestId}")
    public Result<SeckillRequestView> get(@PathVariable String requestId) {
        return Result.success(admissionService.getRequest(requestId));
    }
}
