package com.javalearn.lab.seckill.order.app;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;

/**
 * Order-domain business meters.
 *
 * <p>Two counters share the order write path:</p>
 * <ul>
 *   <li>{@code orders_created_total} counts only transactions that really
 *       inserted an order row and committed.</li>
 *   <li>{@code idempotent_reject} counts a repeated {@code Idempotency-Key}
 *       that resolved to an existing order on the synchronous
 *       {@code POST /api/orders} path only (Task 18 brief). Prometheus
 *       exports it as {@code idempotent_reject_total}. The seckill admission
 *       replay keeps its own {@code seckill_attempts{result="replay"}} series
 *       and duplicate MQ deliveries are counted by
 *       {@code order_consumer_processed{result="duplicate"}}; neither
 *       increments this counter.</li>
 * </ul>
 *
 * <p>Both counters are registered eagerly so their series exist at zero before
 * the first request. Neither carries high-cardinality labels such as userId,
 * orderId or requestId.</p>
 */
public final class OrderMetrics {

    private final Counter ordersCreated;
    private final Counter idempotentReject;

    public OrderMetrics(MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.ordersCreated = Counter.builder("orders_created_total")
                .description("Orders durably created; idempotent replays excluded")
                .register(meterRegistry);
        this.idempotentReject = Counter.builder("idempotent_reject")
                .description("Repeated idempotency keys resolved to an existing order")
                .register(meterRegistry);
    }

    /** Records one committed order insert (never called for a replay). */
    public void orderCreated() {
        ordersCreated.increment();
    }

    /** Records one synchronous-path replay that returned an existing order. */
    public void idempotentReject() {
        idempotentReject.increment();
    }
}
