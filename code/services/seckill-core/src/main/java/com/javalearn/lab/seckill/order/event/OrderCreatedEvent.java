package com.javalearn.lab.seckill.order.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain event emitted after an order row is durably created. No publisher is
 * wired in M1; the type exists so later milestones (MQ, metrics) can consume a
 * stable, framework-neutral payload.
 */
public final class OrderCreatedEvent {

    private final long orderId;
    private final String orderNo;
    private final long productId;
    private final String userId;
    private final int quantity;
    private final Instant occurredAt;

    public OrderCreatedEvent(long orderId,
                             String orderNo,
                             long productId,
                             String userId,
                             int quantity,
                             Instant occurredAt) {
        this.orderId = orderId;
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo");
        this.productId = productId;
        this.userId = Objects.requireNonNull(userId, "userId");
        this.quantity = quantity;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public long getOrderId() {
        return orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public long getProductId() {
        return productId;
    }

    public String getUserId() {
        return userId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
