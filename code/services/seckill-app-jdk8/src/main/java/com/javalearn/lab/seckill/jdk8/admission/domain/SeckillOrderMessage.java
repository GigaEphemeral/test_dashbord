package com.javalearn.lab.seckill.jdk8.admission.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RabbitMQ payload for one accepted seckill request. The idempotency key is
 * carried through so the consumer can delegate to the existing durable order
 * path, whose database unique key remains the final backstop.
 *
 * <p>Java 8 cannot use a record, so the JSON form is pinned with
 * {@link JsonCreator}/{@link JsonProperty}; the property names match the
 * JDK 21 record so a message published by either runtime is consumable by the
 * other.</p>
 */
public final class SeckillOrderMessage {

    private final String requestId;
    private final String idempotencyKey;
    private final String userId;
    private final long productId;
    private final int quantity;

    @JsonCreator
    public SeckillOrderMessage(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("idempotencyKey") String idempotencyKey,
            @JsonProperty("userId") String userId,
            @JsonProperty("productId") long productId,
            @JsonProperty("quantity") int quantity) {
        this.requestId = requestId;
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
    }

    public static SeckillOrderMessage from(SeckillRequest request) {
        return new SeckillOrderMessage(
                request.getRequestId(),
                request.getIdempotencyKey(),
                request.getUserId(),
                request.getProductId(),
                request.getQuantity());
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
}
