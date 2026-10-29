package com.javalearn.lab.seckill.admission.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.admission.config.SeckillProperties;
import com.javalearn.lab.seckill.admission.domain.SeckillOrderMessage;
import com.javalearn.lab.seckill.order.app.OrderApplicationService;
import com.javalearn.lab.seckill.order.app.OrderQueryService;
import com.javalearn.lab.seckill.order.app.OrderRequestSource;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private ThreadPoolExecutor businessExecutor;
    private SeckillOrderConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // A real (tiny) bounded pool, exactly like production: one worker and a
        // ten-slot queue make the saturation path deterministic without sleeps.
        businessExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                new ThreadPoolExecutor.AbortPolicy());
        consumer = new SeckillOrderConsumer(
                properties(),
                requestStore,
                stockStore,
                orderApplicationService,
                orderQueryService,
                new SeckillMetrics(meterRegistry),
                businessExecutor);
    }

    @AfterEach
    void shutdownExecutor() throws InterruptedException {
        businessExecutor.shutdownNow();
        businessExecutor.awaitTermination(2L, TimeUnit.SECONDS);
    }

    @Test
    void createsOrderThroughTheDurableServiceAndMarksRequestCreated() {
        given(orderQueryService.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        given(orderApplicationService.create(any(), any(OrderRequestSource.class))).willReturn(ORDER);

        assertThat(consumer.process(MESSAGE)).isTrue();

        verify(requestStore).markCreated("request-1", 7L);
        verify(stockStore, never()).restore(anyLong(), anyInt());
        assertThat(consumerCounter("created")).isEqualTo(1.0d);
    }

    @Test
    void duplicateDeliveryReturnsExistingOrderAndCountsDuplicate() {
        given(orderQueryService.findByIdempotencyKey("key-1"))
                .willReturn(Optional.of(ORDER));
        given(orderApplicationService.create(any(), any(OrderRequestSource.class))).willReturn(ORDER);

        assertThat(consumer.process(MESSAGE)).isTrue();

        verify(orderApplicationService).create(any(), eq(OrderRequestSource.SECKILL));
        verify(requestStore).markCreated("request-1", 7L);
        assertThat(consumerCounter("duplicate")).isEqualTo(1.0d);
        assertThat(consumerCounter("created")).isZero();
    }

    @Test
    void failureMarksRequestFailedAndRestoresStockBestEffort() {
        given(orderQueryService.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        willThrow(new BizException(CommonErrorCode.CONFLICT, "Sold out"))
                .given(orderApplicationService).create(any(), any(OrderRequestSource.class));
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
        given(orderApplicationService.create(any(), any(OrderRequestSource.class))).willReturn(ORDER);
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
                .given(orderApplicationService).create(any(), any(OrderRequestSource.class));

        assertThat(consumer.process(MESSAGE)).isTrue();

        verify(requestStore).markCreated("request-1", 7L);
        verify(stockStore, never()).restore(anyLong(), anyInt());
        assertThat(consumerCounter("duplicate")).isEqualTo(1.0d);
        assertThat(consumerCounter("failed")).isZero();
    }

    @Test
    void aFullPoolWithAbortPolicyMarksTheRequestFailedAndRestoresTheReservation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // One running task + ten queued fillers = the tiny pool is full.
            businessExecutor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(2L, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 10; i++) {
                businessExecutor.execute(() -> {
                });
            }
            given(stockStore.restore(20L, 1)).willReturn(true);

            // AbortPolicy refuses the consumer's submission synchronously; the
            // listener must compensate exactly like a processing failure.
            assertThatThrownBy(() -> consumer.onMessage(MESSAGE))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                    .hasMessageContaining("rejected by the business pool")
                    .hasMessageContaining("request-1");
        } finally {
            release.countDown();
        }

        verify(requestStore).markFailed(eq("request-1"), anyString());
        verify(stockStore).restore(20L, 1);
        verify(orderApplicationService, never()).create(any(), any(OrderRequestSource.class));
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
