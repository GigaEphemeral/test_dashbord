package com.javalearn.lab.seckill.order.app;

import com.javalearn.lab.seckill.order.api.OrderService;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Read-side service. Each lookup runs in its own read-only transaction, which
 * matters for the duplicate-key retry: after a rollback, the retry must start a
 * fresh transaction to see the winner's committed row (MySQL's default
 * REPEATABLE READ snapshot is created per transaction).
 */
public class OrderQueryService {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public OrderQueryService(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = Objects.requireNonNull(orderService, "orderService");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository");
    }

    @Transactional(readOnly = true)
    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return orderRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Transactional(readOnly = true)
    public Order get(long id) {
        return orderService.get(id);
    }
}
