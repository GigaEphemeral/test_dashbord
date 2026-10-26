package com.javalearn.lab.seckill.admission.app;

/**
 * Redis idempotency claim. {@code SET NX EX} is the admission-side guard;
 * the database unique key on the order remains the durable backstop.
 */
public interface SeckillIdempotencyStore {

    /**
     * Claims the idempotency key for the given request id.
     *
     * @return {@code null} when this caller won the claim, otherwise the
     *         request id stored by the first caller
     */
    String claim(String idempotencyKey, String requestId);

    /** Releases a claim after a failure that produced no accepted request. */
    void release(String idempotencyKey);
}
