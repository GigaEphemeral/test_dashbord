package com.javalearn.lab.seckill.admission.domain;

import java.util.Objects;

/**
 * Redis-backed state of one async admission. It is stored before the MQ
 * message is published and updated by the consumer, so polling, idempotent
 * replay and rollback all read the same record.
 */
public record SeckillRequest(
        String requestId,
        String idempotencyKey,
        String userId,
        long productId,
        int quantity,
        SeckillRequestStatus status,
        Long orderId,
        String message) {

    public SeckillRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
    }

    public static SeckillRequest accepted(String requestId,
                                          String idempotencyKey,
                                          String userId,
                                          long productId,
                                          int quantity) {
        return new SeckillRequest(requestId, idempotencyKey, userId, productId, quantity,
                SeckillRequestStatus.ACCEPTED, null, null);
    }

    public SeckillRequest created(long durableOrderId) {
        return new SeckillRequest(requestId, idempotencyKey, userId, productId, quantity,
                SeckillRequestStatus.CREATED, durableOrderId, null);
    }

    public SeckillRequest failed(String failureMessage) {
        return new SeckillRequest(requestId, idempotencyKey, userId, productId, quantity,
                SeckillRequestStatus.FAILED, null, failureMessage);
    }
}
