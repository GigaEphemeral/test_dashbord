package com.javalearn.lab.seckill.jdk8.order.app;

import com.javalearn.lab.seckill.order.api.OrderService;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Read-side service.
 *
 * <p>These lookups are deliberately plain {@code @Transactional} (read-write),
 * <em>not</em> {@code readOnly = true}: both calls must be read-after-write
 * consistent. The duplicate-key retry re-reads the winner's just-committed row,
 * and {@code GET /api/orders/{id}} must find the order the POST just returned.
 * The JDK 8 shell has no read replica behind this module, so the distinction
 * only matters for parity with the JDK 21 routing datasource contract.</p>
 *
 * <p>Each lookup still runs in its own transaction, which is what matters for
 * the duplicate-key retry: after a rollback, the retry must start a fresh
 * transaction to see the winner's committed row.</p>
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
