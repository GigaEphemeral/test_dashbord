package com.javalearn.lab.seckill.order.app;

import com.javalearn.lab.seckill.order.api.CreateOrderCommand;
import com.javalearn.lab.seckill.order.api.OrderService;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * Transaction boundary for order creation. Stock reservation and the order
 * insert share one transaction, so a failed insert (including a concurrent
 * duplicate idempotency key) rolls the reservation back.
 *
 * <p>{@code orders_created_total} is incremented only after a transaction that
 * really inserted a row commits; an idempotent replay (detected by the
 * pre-check) and a losing duplicate-key race (which throws before returning)
 * do not touch the counter. The pre-check is only a metrics hint; correctness
 * still comes from the database unique key.</p>
 *
 * <p>OpenMetrics reserves the {@code _created} suffix, so the Prometheus Java
 * client exports this meter family as {@code orders_total} in the raw Actuator
 * scrape. {@code prometheus.yml} aliases it back to
 * {@code orders_created_total} at scrape time.</p>
 *
 * <p>The replay pre-check and the core lookup run in the same transaction, so
 * under MySQL's default REPEATABLE READ they share one snapshot and the
 * counter is exact. Idempotency itself never depends on this check: the unique
 * key remains authoritative under any isolation level.</p>
 */
public class OrderTransactionService {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final Counter ordersCreated;

    public OrderTransactionService(OrderService orderService,
                                   OrderRepository orderRepository,
                                   MeterRegistry meterRegistry) {
        this.orderService = Objects.requireNonNull(orderService, "orderService");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository");
        this.ordersCreated = Counter.builder("orders_created_total")
                .description("Orders durably created; idempotent replays excluded")
                .register(Objects.requireNonNull(meterRegistry, "meterRegistry"));
    }

    @Transactional
    public Order create(CreateOrderCommand command) {
        Objects.requireNonNull(command, "command");
        boolean replay = command.getIdempotencyKey() != null
                && orderRepository.findByIdempotencyKey(command.getIdempotencyKey()).isPresent();

        Order order = orderService.create(command);
        if (!replay) {
            recordCreatedAfterCommit();
        }
        return order;
    }

    private void recordCreatedAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ordersCreated.increment();
                }
            });
        } else {
            ordersCreated.increment();
        }
    }
}
