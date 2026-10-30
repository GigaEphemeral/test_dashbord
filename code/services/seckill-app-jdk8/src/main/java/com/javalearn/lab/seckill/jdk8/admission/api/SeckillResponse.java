package com.javalearn.lab.seckill.jdk8.admission.api;

import com.javalearn.lab.seckill.jdk8.admission.domain.SeckillRequest;

/**
 * Response of {@code POST /api/seckill/orders}.
 *
 * <p>Async mode returns HTTP 202 with {@code status=ACCEPTED} (or the current
 * status when the idempotency key is replayed). The synchronous toggle returns
 * HTTP 200 with {@code status=CREATED} and the durable order id; it has no
 * async request id, so {@code requestId} is {@code null} on that path.</p>
 */
public class SeckillResponse {

    private final String requestId;
    private final String status;
    private final Long orderId;

    public SeckillResponse(String requestId, String status, Long orderId) {
        this.requestId = requestId;
        this.status = status;
        this.orderId = orderId;
    }

    public static SeckillResponse from(SeckillRequest request) {
        return new SeckillResponse(
                request.getRequestId(),
                request.getStatus().name(),
                request.getOrderId());
    }

    public String getRequestId() {
        return requestId;
    }

    public String getStatus() {
        return status;
    }

    public Long getOrderId() {
        return orderId;
    }
}
