package com.javalearn.lab.seckill.admission.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.admission.config.SeckillProperties;
import com.javalearn.lab.seckill.admission.domain.SeckillOrderMessage;
import com.javalearn.lab.seckill.order.app.OrderApplicationService;
import com.javalearn.lab.seckill.order.app.OrderQueryService;
import com.javalearn.lab.seckill.order.domain.Order;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Consumer behaviour with fakes: it must reuse the durable order service,
 * mark the request state, count duplicates and compensate a failure exactly
 * once.
 */
@ExtendWith(MockitoExtension.class)
class SeckillOrderConsumerTest {

    private static final SeckillOrderMessage MESSAGE =
            new SeckillOrderMessage("request-1", "key-1", "u1", 20L, 1);
    private static final Order ORDER = new Order(
            7L, "order-no-7", "u1", 20L, 1, OrderStatus.CREATED, "key-1",
            Instant.parse("2026-10-26T08:00:00Z"));

    @Mock
    private SeckillRequestStore requestStore;

    @Mock
    private SeckillStockStore stockStore;

    @Mock
    private OrderApplicationService orderApplicationService;

    @Mock
    private OrderQueryService orderQueryService;

    private SimpleMeterRegistry meterRegistry;
    private SeckillOrderConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new SeckillOrderConsumer(
                properties(),
                requestStore,
                stockStore,
                orderApplicationService,
                orderQueryService,
                new SeckillMetrics(meterRegistry));
    }

    @Test
    void createsOrderThroughTheDurableServiceAndMarksRequestCreated() {
        given(orderQueryService.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        given(orderApplicationService.create(any())).willReturn(ORDER);

        assertThat(consumer.process(MESSAGE)).isTrue();

        verify(requestStore).markCreated("request-1", 7L);
        verify(stockStore, never()).restore(anyLong(), anyInt());
        assertThat(consumerCounter("created")).isEqualTo(1.0d);
    }

    @Test
    void duplicateDeliveryReturnsExistingOrderAndCountsDuplicate() {
        given(orderQueryService.findByIdempotencyKey("key-1"))
                .willReturn(Optional.of(ORDER));
        given(orderApplicationService.create(any())).willReturn(ORDER);

        assertThat(consumer.process(MESSAGE)).isTrue();

        verify(orderApplicationService).create(any());
        verify(requestStore).markCreated("request-1", 7L);
        assertThat(consumerCounter("duplicate")).isEqualTo(1.0d);
        assertThat(consumerCounter("created")).isZero();
    }

    @Test
    void failureMarksRequestFailedAndRestoresStockBestEffort() {
        given(orderQueryService.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        willThrow(new BizException(CommonErrorCode.CONFLICT, "Sold out"))
                .given(orderApplicationService).create(any());
        given(stockStore.restore(20L, 1)).willReturn(true);

        assertThat(consumer.process(MESSAGE)).isFalse();

        verify(requestStore).markFailed("request-1", "Sold out");
        verify(stockStore).restore(20L, 1);
        verify(requestStore, never()).markCreated(anyString(), anyLong());
        assertThat(consumerCounter("failed")).isEqualTo(1.0d);
    }

    @Test
    void stateUpdateFailureAfterDurableInsertNeverRestoresStock() {
        given(orderQueryService.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        given(orderApplicationService.create(any())).willReturn(ORDER);
        willThrow(new IllegalStateException("redis down"))
                .given(requestStore).markCreated("request-1", 7L);

        assertThat(consumer.process(MESSAGE)).isTrue();

        verify(stockStore, never()).restore(anyLong(), anyInt());
        assertThat(consumerCounter("created")).isEqualTo(1.0d);
    }

    @Test
    void existingOrderPlusFailedRedeliveryNeverRestoresStock() {
        given(orderQueryService.findByIdempotencyKey("key-1")).willReturn(Optional.of(ORDER));
        willThrow(new IllegalStateException("database unavailable"))
                .given(orderApplicationService).create(any());

        assertThat(consumer.process(MESSAGE)).isTrue();

        verify(requestStore).markCreated("request-1", 7L);
        verify(stockStore, never()).restore(anyLong(), anyInt());
        assertThat(consumerCounter("duplicate")).isEqualTo(1.0d);
        assertThat(consumerCounter("failed")).isZero();
    }

    private double consumerCounter(String result) {
        Counter counter = meterRegistry.find("order_consumer_processed")
                .tag("result", result)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static SeckillProperties properties() {
        SeckillProperties properties = new SeckillProperties();
        properties.setAsyncEnabled(true);
        properties.getConsumer().setDelayMs(0L);
        return properties;
    }
}
