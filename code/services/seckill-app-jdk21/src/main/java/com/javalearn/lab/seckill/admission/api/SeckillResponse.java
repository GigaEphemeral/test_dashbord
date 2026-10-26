package com.javalearn.lab.seckill.admission.api;

import com.javalearn.lab.seckill.admission.domain.SeckillRequest;

/**
 * Response of {@code POST /api/seckill/orders}.
 *
 * <p>Async mode returns HTTP 202 with {@code status=ACCEPTED} (or the current
 * status when the idempotency key is replayed). The synchronous toggle returns
 * HTTP 200 with {@code status=CREATED} and the durable order id; it has no
 * async request id, so {@code requestId} is {@code null} on that path.</p>
 */
public record SeckillResponse(String requestId, String status, Long orderId) {

    public static SeckillResponse from(SeckillRequest request) {
        return new SeckillResponse(
                request.requestId(),
                request.status().name(),
                request.orderId());
    }
}
