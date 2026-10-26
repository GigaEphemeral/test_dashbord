package com.javalearn.lab.seckill.order.api;

import com.javalearn.lab.seckill.order.domain.Order;

/**
 * Core order use case. Callers must provide the transaction boundary; the
 * service itself performs only domain work (validation, idempotency lookup,
 * stock reservation, order creation).
 */
public interface OrderService {

    Order create(CreateOrderCommand command);

    Order get(long id);
}
