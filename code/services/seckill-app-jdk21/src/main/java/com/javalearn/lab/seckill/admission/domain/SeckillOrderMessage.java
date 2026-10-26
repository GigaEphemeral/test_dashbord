package com.javalearn.lab.seckill.admission.domain;

/**
 * RabbitMQ payload for one accepted seckill request. The idempotency key is
 * carried through so the consumer can delegate to the existing durable order
 * path, whose database unique key remains the final backstop.
 */
public record SeckillOrderMessage(
        String requestId,
        String idempotencyKey,
        String userId,
        long productId,
        int quantity) {

    public static SeckillOrderMessage from(SeckillRequest request) {
        return new SeckillOrderMessage(
                request.requestId(),
                request.idempotencyKey(),
                request.userId(),
                request.productId(),
                request.quantity());
    }
}
