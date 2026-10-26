package com.javalearn.lab.seckill.admission.domain;

/**
 * Lifecycle of an async seckill request as observed through
 * {@code GET /api/seckill/orders/{requestId}}.
 */
public enum SeckillRequestStatus {

    /** Redis stock was reserved and the message was published; the order does not exist yet. */
    ACCEPTED,

    /** The consumer created the durable order (or found it through the idempotency backstop). */
    CREATED,

    /** The consumer could not create the order; Redis stock was restored best-effort. */
    FAILED
}
