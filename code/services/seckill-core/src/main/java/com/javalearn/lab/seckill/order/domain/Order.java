package com.javalearn.lab.seckill.order.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable order aggregate. The id is {@code 0} until the persistence adapter
 * stores the order; {@link #withId(long)} returns the saved representation.
 */
public final class Order {

    private final long id;
    private final String orderNo;
    private final String userId;
    private final long productId;
    private final int quantity;
    private final OrderStatus status;
    private final String idempotencyKey;
    private final Instant createdAt;

    public Order(long id,
                 String orderNo,
                 String userId,
                 long productId,
                 int quantity,
                 OrderStatus status,
                 String idempotencyKey,
                 Instant createdAt) {
        this.id = id;
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.productId = productId;
        this.quantity = quantity;
        this.status = Objects.requireNonNull(status, "status");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
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

    public OrderStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Order withId(long newId) {
        return new Order(newId, orderNo, userId, productId, quantity, status,
                idempotencyKey, createdAt);
    }
}
