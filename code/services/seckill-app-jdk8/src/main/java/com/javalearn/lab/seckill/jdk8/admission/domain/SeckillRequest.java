package com.javalearn.lab.seckill.jdk8.admission.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Redis-backed state of one async admission. It is stored before the MQ
 * message is published and updated by the consumer, so polling, idempotent
 * replay and rollback all read the same record.
 *
 * <p>The JSON property names match the JDK 21 record byte-for-byte
 * ({@code requestId}, {@code idempotencyKey}, {@code userId},
 * {@code productId}, {@code quantity}, {@code status}, {@code orderId},
 * {@code message}), so the two runtimes can share the same Redis keys during a
 * rolling swap.</p>
 */
public final class SeckillRequest {

    private final String requestId;
    private final String idempotencyKey;
    private final String userId;
    private final long productId;
    private final int quantity;
    private final SeckillRequestStatus status;
    private final Long orderId;
    private final String message;

    @JsonCreator
    public SeckillRequest(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("idempotencyKey") String idempotencyKey,
            @JsonProperty("userId") String userId,
            @JsonProperty("productId") long productId,
            @JsonProperty("quantity") int quantity,
            @JsonProperty("status") SeckillRequestStatus status,
            @JsonProperty("orderId") Long orderId,
            @JsonProperty("message") String message) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.productId = productId;
        this.quantity = quantity;
        this.status = Objects.requireNonNull(status, "status");
        this.orderId = orderId;
        this.message = message;
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

    public String getRequestId() {
        return requestId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getUserId() {
        return userId;
    }

    public long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public SeckillRequestStatus getStatus() {
        return status;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getMessage() {
        return message;
    }
}
