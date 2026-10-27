package com.javalearn.lab.seckill.admission.app;

import com.javalearn.lab.seckill.admission.config.SeckillProperties;
import com.javalearn.lab.seckill.admission.domain.SeckillOrderMessage;
import com.javalearn.lab.seckill.inventory.infra.InventoryStockJpaRepository;
import com.javalearn.lab.seckill.inventory.infra.JpaInventoryRepositoryAdapter;
import com.javalearn.lab.seckill.order.api.OrderService;
import com.javalearn.lab.seckill.order.app.DefaultOrderService;
import com.javalearn.lab.seckill.order.app.OrderApplicationService;
import com.javalearn.lab.seckill.order.app.OrderMetrics;
import com.javalearn.lab.seckill.order.app.OrderQueryService;
import com.javalearn.lab.seckill.order.app.OrderTransactionService;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import com.javalearn.lab.seckill.order.domain.OrderStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Asynchronous seckill consumer and {@code stock_oversold_total}.
 *
 * <p>The consumer drives the same durable order use case as the synchronous
 * path, so a Redis-over-reservation that reaches the database is refused by
 * the conditional UPDATE (0 rows affected). One blocked attempt must count
 * exactly once on the shared counter — the consumer's own
 * {@code order_consumer_processed{result="failed"}} series stays separate.</p>
 */
@ExtendWith(MockitoExtension.class)
class SeckillConsumerOversoldCounterTest {

    private static final SeckillOrderMessage MESSAGE = new SeckillOrderMessage(
            "req-1", "consumer-oversold-key", "u1", 20L, 1);

    @Mock
    private SeckillRequestStore requestStore;

    @Mock
    private SeckillStockStore stockStore;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderQueryService orderQueryService;

    @Mock
    private InventoryStockJpaRepository inventoryStockJpaRepository;

    private SimpleMeterRegistry meterRegistry;
    private SeckillMetrics seckillMetrics;
    private SeckillOrderConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        seckillMetrics = new SeckillMetrics(meterRegistry);
        OrderMetrics orderMetrics = new OrderMetrics(meterRegistry);
        JpaInventoryRepositoryAdapter inventoryRepository =
                new JpaInventoryRepositoryAdapter(inventoryStockJpaRepository, meterRegistry);
        OrderService orderService = new DefaultOrderService(orderRepository, inventoryRepository);
        OrderTransactionService transactionService =
                new OrderTransactionService(orderService, orderRepository, orderMetrics);
        OrderApplicationService applicationService =
                new OrderApplicationService(transactionService, orderQueryService, orderMetrics);
        consumer = new SeckillOrderConsumer(new SeckillProperties(), requestStore, stockStore,
                applicationService, orderQueryService, seckillMetrics);
    }

    @Test
    void databaseRefusedReservationCountsOnePreventedOversell() {
        given(orderQueryService.findByIdempotencyKey("consumer-oversold-key"))
                .willReturn(Optional.empty());
        given(orderRepository.findByIdempotencyKey("consumer-oversold-key"))
                .willReturn(Optional.empty());
        // Redis admitted the request, but the database guard had already sold
        // the last unit: the conditional UPDATE matches zero rows.
        given(inventoryStockJpaRepository.reserve(20L, 1)).willReturn(0);
        given(stockStore.restore(20L, 1)).willReturn(true);

        assertThat(consumer.process(MESSAGE)).isFalse();

        assertThat(stockOversold()).isEqualTo(1.0d);
        // The compensation stays on its own series: one failed consumer
        // handling, one prevented oversell — never double-counted.
        assertThat(consumerProcessed("failed")).isEqualTo(1.0d);
        assertThat(consumerProcessed("created")).isZero();
        verify(requestStore).markFailed(eq("req-1"), anyString());
    }

    @Test
    void successfulConsumerOrderDoesNotIncrement() {
        Order created = new Order(7L, "order-no-7", "u1", 20L, 1, OrderStatus.CREATED,
                "consumer-oversold-key", Instant.parse("2026-10-26T08:00:00Z"));
        given(orderQueryService.findByIdempotencyKey("consumer-oversold-key"))
                .willReturn(Optional.empty());
        given(orderRepository.findByIdempotencyKey("consumer-oversold-key"))
                .willReturn(Optional.empty());
        given(inventoryStockJpaRepository.reserve(20L, 1)).willReturn(1);
        given(orderRepository.save(any(Order.class))).willReturn(created);

        assertThat(consumer.process(MESSAGE)).isTrue();

        assertThat(stockOversold()).isZero();
        assertThat(consumerProcessed("created")).isEqualTo(1.0d);
    }

    private double stockOversold() {
        Counter counter = meterRegistry
                .find(JpaInventoryRepositoryAdapter.STOCK_OVERSOLD_METRIC)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    private double consumerProcessed(String result) {
        Counter counter = meterRegistry.find("order_consumer_processed")
                .tag("result", result)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }
}
