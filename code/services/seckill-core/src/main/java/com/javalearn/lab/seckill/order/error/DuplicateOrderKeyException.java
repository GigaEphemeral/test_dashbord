package com.javalearn.lab.seckill.order.error;

/**
 * Thrown by the persistence adapter when the database rejects an insert
 * because {@code order_no} or {@code idempotency_key} is already present.
 * This is a race signal, not a business failure: the application layer retries
 * the idempotent lookup and returns the winner's order.
 */
public class DuplicateOrderKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateOrderKeyException(String message) {
        super(message);
    }

    public DuplicateOrderKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
