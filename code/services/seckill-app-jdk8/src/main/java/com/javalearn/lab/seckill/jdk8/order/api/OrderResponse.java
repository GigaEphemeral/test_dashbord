package com.javalearn.lab.seckill.jdk8.order.api;

import com.javalearn.lab.seckill.order.domain.Order;

import java.time.Instant;

/**
 * HTTP representation of an order. Same field names as the JDK 21 record, so
 * the k6 flow and the console read either runtime unchanged.
 */
public class OrderResponse {

    private final long id;
    private final String orderNo;
    private final String userId;
    private final long productId;
    private final int quantity;
    private final String status;
    private final Instant createdAt;

    public OrderResponse(long id,
                         String orderNo,
                         String userId,
                         long productId,
                         int quantity,
                         String status,
                         Instant createdAt) {
        this.id = id;
        this.orderNo = orderNo;
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNo(),
                order.getUserId(),
                order.getProductId(),
                order.getQuantity(),
                order.getStatus().name(),
                order.getCreatedAt());
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

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
