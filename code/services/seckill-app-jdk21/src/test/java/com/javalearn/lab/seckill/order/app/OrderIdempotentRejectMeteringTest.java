package com.javalearn.lab.seckill.order.app;

import com.javalearn.lab.seckill.order.api.CreateOrderCommand;
import com.javalearn.lab.seckill.order.api.OrderService;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import com.javalearn.lab.seckill.order.domain.OrderStatus;
import com.javalearn.lab.seckill.order.error.DuplicateOrderKeyException;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * Scope of the {@code idempotent_reject} counter (Task 18 brief line 11).
 *
 * <p>A repeated {@code Idempotency-Key} that resolves to an existing order is
 * counted only when the request came from the synchronous
 * {@code POST /api/orders} path. The seckill admission fallback and the MQ
 * consumer share this durable order code; their replays must stay on
 * {@code seckill_attempts{result="replay"}} and
 * {@code order_consumer_processed{result="duplicate"}} respectively, and must
 * never inflate {@code idempotent_reject}. {@code orders_created_total} keeps
 * counting every real insert regardless of the caller.</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderIdempotentRejectMeteringTest {

    private static final CreateOrderCommand COMMAND =
            new CreateOrderCommand("u1", 20L, 1, "key-1");
    private static final Order EXISTING = new Order(
            7L, "order-no-7", "u1", 20L, 1, OrderStatus.CREATED, "key-1",
            Instant.parse("2026-10-26T08:00:00Z"));

    @Mock
    private OrderService orderService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderQueryService orderQueryService;

    private SimpleMeterRegistry meterRegistry;
    private OrderMetrics orderMetrics;
    private OrderTransactionService transactionService;
    private OrderApplicationService applicationService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        orderMetrics = new OrderMetrics(meterRegistry);
        transactionService = new OrderTransactionService(orderService, orderRepository, orderMetrics);
        applicationService = new OrderApplicationService(transactionService, orderQueryService, orderMetrics);
    }

    @Test
    void syncApiPreCheckReplayCountsIdempotentReject() {
        given(orderRepository.findByIdempotencyKey("key-1")).willReturn(Optional.of(EXISTING));
        given(orderService.create(COMMAND)).willReturn(EXISTING);

        Order order = transactionService.create(COMMAND, OrderRequestSource.SYNC_API);

        assertThat(order.getId()).isEqualTo(7L);
        assertThat(idempotentReject()).isEqualTo(1.0d);
        assertThat(ordersCreated()).isZero();
    }

    @Test
    void consumerStyleSeckillPreCheckReplayDoesNotCountIdempotentReject() {
        given(orderRepository.findByIdempotencyKey("key-1")).willReturn(Optional.of(EXISTING));
        given(orderService.create(COMMAND)).willReturn(EXISTING);

        Order order = transactionService.create(COMMAND, OrderRequestSource.SECKILL);

        assertThat(order.getId()).isEqualTo(7L);
        assertThat(idempotentReject()).isZero();
        assertThat(ordersCreated()).isZero();
    }

    @Test
    void syncApiDuplicateKeyRaceCountsIdempotentRejectOnce() {
        willThrow(new DuplicateOrderKeyException("dup")).given(orderService).create(COMMAND);
        given(orderQueryService.findByIdempotencyKey("key-1")).willReturn(Optional.of(EXISTING));

        Order order = applicationService.create(COMMAND, OrderRequestSource.SYNC_API);

        assertThat(order.getId()).isEqualTo(7L);
        assertThat(idempotentReject()).isEqualTo(1.0d);
    }

    @Test
    void seckillDuplicateKeyRaceDoesNotCountIdempotentReject() {
        willThrow(new DuplicateOrderKeyException("dup")).given(orderService).create(COMMAND);
        given(orderQueryService.findByIdempotencyKey("key-1")).willReturn(Optional.of(EXISTING));

        Order order = applicationService.create(COMMAND, OrderRequestSource.SECKILL);

        assertThat(order.getId()).isEqualTo(7L);
        assertThat(idempotentReject()).isZero();
    }

    @Test
    void realInsertCountsOrdersCreatedForEverySource() {
        given(orderRepository.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        given(orderService.create(any(CreateOrderCommand.class))).willReturn(EXISTING);

        transactionService.create(COMMAND, OrderRequestSource.SYNC_API);
        assertThat(ordersCreated()).isEqualTo(1.0d);
        assertThat(idempotentReject()).isZero();

        transactionService.create(COMMAND, OrderRequestSource.SECKILL);
        assertThat(ordersCreated()).isEqualTo(2.0d);
        assertThat(idempotentReject()).isZero();
    }

    private double idempotentReject() {
        return counter("idempotent_reject");
    }

    private double ordersCreated() {
        return counter("orders_created_total");
    }

    private double counter(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
