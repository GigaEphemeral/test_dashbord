package com.javalearn.lab.seckill.admission.api;

/**
 * HTTP body for {@code POST /api/seckill/orders}. Field validation belongs to
 * the admission service so both the async and the synchronous fallback path
 * apply exactly the same rules as the core order use case.
 */
public record CreateSeckillOrderRequest(String userId, long productId, int quantity) {
}
