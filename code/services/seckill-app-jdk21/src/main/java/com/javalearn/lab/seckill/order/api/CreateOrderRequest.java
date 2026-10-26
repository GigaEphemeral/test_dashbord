package com.javalearn.lab.seckill.order.api;

/**
 * HTTP request body for {@code POST /api/orders}. Field validation belongs to
 * the core use case so the same rules apply to the future seckill endpoint.
 */
public record CreateOrderRequest(String userId, long productId, int quantity) {
}
