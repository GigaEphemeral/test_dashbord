package com.javalearn.lab.seckill.order.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.inventory.infra.InventoryStockJpaRepository;
import com.javalearn.lab.seckill.inventory.infra.JpaInventoryRepositoryAdapter;
import com.javalearn.lab.seckill.order.api.CreateOrderCommand;
import com.javalearn.lab.seckill.order.api.OrderService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Synchronous order path ({@code POST /api/orders}) and {@code stock_oversold_total}.
 *
 * <p>Runs the real use-case chain (application service → transaction service →
 * {@code DefaultOrderService} → {@link JpaInventoryRepositoryAdapter}) with the
 * JPA repository stubbed at the affected-row count of the conditional UPDATE,
 * which is where MySQL reports a prevented oversell (0 rows). The counter must
 * increment exactly once per blocked attempt, on this path only, without
 * double-counting together with the async path.</p>
 */
@ExtendWith(MockitoExtension.class)
class SyncOrderOversoldCounterTest {

    private static final CreateOrderCommand COMMAND =
            new CreateOrderCommand("u1", 20L, 1, "sync-oversold-key");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderQueryService orderQueryService;

    @Mock
    private InventoryStockJpaRepository inventoryStockJpaRepository;

    private SimpleMeterRegistry meterRegistry;
    private OrderApplicationService applicationService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        OrderMetrics orderMetrics = new OrderMetrics(meterRegistry);
        JpaInventoryRepositoryAdapter inventoryRepository =
                new JpaInventoryRepositoryAdapter(inventoryStockJpaRepository, meterRegistry);
        OrderService orderService = new DefaultOrderService(orderRepository, inventoryRepository);
        OrderTransactionService transactionService =
                new OrderTransactionService(orderService, orderRepository, orderMetrics);
        applicationService =
                new OrderApplicationService(transactionService, orderQueryService, orderMetrics);
    }

    @Test
    void soldOutSyncOrderCountsOnePreventedOversell() {
        given(orderRepository.findByIdempotencyKey("sync-oversold-key")).willReturn(Optional.empty());
        given(inventoryStockJpaRepository.reserve(20L, 1)).willReturn(0);

        assertThatThrownBy(() -> applicationService.create(COMMAND, OrderRequestSource.SYNC_API))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.CONFLICT);

        assertThat(stockOversold()).isEqualTo(1.0d);
    }

    @Test
    void successfulSyncOrderDoesNotIncrement() {
        Order created = new Order(7L, "order-no-7", "u1", 20L, 1, OrderStatus.CREATED,
                "sync-oversold-key", Instant.parse("2026-10-26T08:00:00Z"));
        given(orderRepository.findByIdempotencyKey("sync-oversold-key")).willReturn(Optional.empty());
        given(inventoryStockJpaRepository.reserve(20L, 1)).willReturn(1);
        given(orderRepository.save(any(Order.class))).willReturn(created);

        Order order = applicationService.create(COMMAND, OrderRequestSource.SYNC_API);

        assertThat(order.getId()).isEqualTo(7L);
        assertThat(stockOversold()).isZero();
    }

    private double stockOversold() {
        Counter counter = meterRegistry
                .find(JpaInventoryRepositoryAdapter.STOCK_OVERSOLD_METRIC)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }
}
