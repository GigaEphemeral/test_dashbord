package com.javalearn.lab.seckill.order.domain;

import java.util.Optional;

/**
 * Framework-neutral order persistence port. The adapter must enforce the
 * uniqueness of both {@code orderNo} and {@code idempotencyKey} at the database
 * level and throw
 * {@link com.javalearn.lab.seckill.order.error.DuplicateOrderKeyException} on a
 * key violation.
 */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(long id);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);
}
