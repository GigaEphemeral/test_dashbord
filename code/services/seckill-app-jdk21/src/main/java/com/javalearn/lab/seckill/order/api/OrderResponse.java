package com.javalearn.lab.seckill.order.api;

import com.javalearn.lab.seckill.order.domain.Order;

import java.time.Instant;

/**
 * HTTP representation of an order.
 */
public record OrderResponse(
        long id,
        String orderNo,
        String userId,
        long productId,
        int quantity,
        String status,
        Instant createdAt) {

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
}
