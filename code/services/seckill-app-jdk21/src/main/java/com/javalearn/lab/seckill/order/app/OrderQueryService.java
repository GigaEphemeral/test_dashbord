package com.javalearn.lab.seckill.order.app;

import com.javalearn.lab.seckill.order.api.OrderService;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Read-side service.
 *
 * <p>Task 15 review fix: these lookups are deliberately plain
 * {@code @Transactional} (read-write), <em>not</em> {@code readOnly = true}.
 * The routing datasource sends read-only transactions to the read pool, which
 * from M3 on is the Router 6447 replica, and both calls here must be
 * read-after-write consistent: the duplicate-key retry re-reads the winner's
 * just-committed row, and {@code GET /api/orders/{id}} must find the order the
 * POST just returned. Lag-tolerant reads (the catalog cache loader) are the
 * ones that go through a dedicated read-only service; see
 * {@code OrderQueryRoutingTest}.</p>
 *
 * <p>Each lookup still runs in its own transaction, which is what matters for
 * the duplicate-key retry: after a rollback, the retry must start a fresh
 * transaction to see the winner's committed row (MySQL's default REPEATABLE
 * READ snapshot is created per transaction).</p>
 */
public class OrderQueryService {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public OrderQueryService(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = Objects.requireNonNull(orderService, "orderService");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository");
    }

    @Transactional
    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return orderRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Transactional
    public Order get(long id) {
        return orderService.get(id);
    }
}
