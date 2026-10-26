package com.javalearn.lab.seckill.admission.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.admission.api.CreateSeckillOrderRequest;
import com.javalearn.lab.seckill.admission.api.SeckillResponse;
import com.javalearn.lab.seckill.admission.config.SeckillProperties;
import com.javalearn.lab.seckill.admission.domain.SeckillRequest;
import com.javalearn.lab.seckill.order.app.OrderApplicationService;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Acceptance logic with fakes: lock timeout, sold out, missing stock, replay,
 * publish rollback, validation and the synchronous toggle.
 */
@ExtendWith(MockitoExtension.class)
class SeckillAdmissionServiceTest {

    private static final long PRODUCT_ID = 20L;

    @Mock
    private SeckillLockGuard lockGuard;

    @Mock
    private SeckillLockGuard.LockHandle lockHandle;

    @Mock
    private SeckillIdempotencyStore idempotencyStore;

    @Mock
    private SeckillStockStore stockStore;

    @Mock
    private SeckillRequestStore requestStore;

    @Mock
    private SeckillMessagePublisher publisher;

    @Mock
    private OrderApplicationService orderApplicationService;

    private SimpleMeterRegistry meterRegistry;
    private SeckillAdmissionService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new SeckillAdmissionService(
                properties(true),
                lockGuard,
                idempotencyStore,
                stockStore,
                requestStore,
                publisher,
                orderApplicationService,
                new SeckillMetrics(meterRegistry));
    }

    @Test
    void acceptedRequestReservesStockStoresStateAndPublishes() {
        given(lockGuard.tryLock(PRODUCT_ID)).willReturn(lockHandle);
        given(idempotencyStore.claim(eq("key-1"), anyString())).willReturn(null);
        given(stockStore.reserve(PRODUCT_ID, 1))
                .willReturn(new SeckillStockStore.Reservation(
                        SeckillStockStore.ReservationStatus.RESERVED, 9L));

        SeckillResponse response = service.admit("key-1", request(1));

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.requestId()).isNotBlank();
        assertThat(response.orderId()).isNull();

        ArgumentCaptor<SeckillRequest> state = ArgumentCaptor.forClass(SeckillRequest.class);
        verify(requestStore).save(state.capture());
        assertThat(state.getValue().idempotencyKey()).isEqualTo("key-1");
        assertThat(state.getValue().status().name()).isEqualTo("ACCEPTED");
        verify(publisher).publish(any());
        verify(lockHandle).close();

        assertThat(attempts("accepted")).isEqualTo(1.0d);
        assertThat(meterRegistry.get("order_accept_seconds").timer().count()).isEqualTo(1L);
    }

    @Test
    void replayReturnsStoredRequestWithoutReservingOrPublishing() {
        given(lockGuard.tryLock(PRODUCT_ID)).willReturn(lockHandle);
        given(idempotencyStore.claim(eq("key-1"), anyString())).willReturn("request-existing");
        given(requestStore.find("request-existing")).willReturn(Optional.of(
                SeckillRequest.accepted("request-existing", "key-1", "u1", PRODUCT_ID, 1)
                        .created(77L)));

        SeckillResponse response = service.admit("key-1", request(1));

        assertThat(response.requestId()).isEqualTo("request-existing");
        assertThat(response.status()).isEqualTo("CREATED");
        assertThat(response.orderId()).isEqualTo(77L);
        verifyNoInteractions(stockStore, publisher);
        verify(requestStore, never()).save(any());
        assertThat(attempts("replay")).isEqualTo(1.0d);
    }

    @Test
    void lockTimeoutIsConflictAndCountsTheMetric() {
        given(lockGuard.tryLock(PRODUCT_ID)).willReturn(null);

        assertThatThrownBy(() -> service.admit("key-1", request(1)))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo("Seckill lock timeout");
                });

        verifyNoInteractions(idempotencyStore, stockStore, requestStore, publisher);
        assertThat(attempts("lock_timeout")).isEqualTo(1.0d);
        assertThat(meterRegistry.get("order_accept_seconds").timer().count()).isEqualTo(1L);
    }

    @Test
    void soldOutIsConflictReleasesTheClaimAndNeverPublishes() {
        given(lockGuard.tryLock(PRODUCT_ID)).willReturn(lockHandle);
        given(idempotencyStore.claim(eq("key-1"), anyString())).willReturn(null);
        given(stockStore.reserve(PRODUCT_ID, 1))
                .willReturn(new SeckillStockStore.Reservation(
                        SeckillStockStore.ReservationStatus.SOLD_OUT, 0L));

        assertThatThrownBy(() -> service.admit("key-1", request(1)))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo("Sold out");
                });

        verify(idempotencyStore).release("key-1");
        verify(requestStore, never()).save(any());
        verifyNoInteractions(publisher);
        verify(lockHandle).close();
        assertThat(attempts("sold_out")).isEqualTo(1.0d);
    }

    @Test
    void missingStockKeyIsServiceUnavailableAndReleasesTheClaim() {
        given(lockGuard.tryLock(PRODUCT_ID)).willReturn(lockHandle);
        given(idempotencyStore.claim(eq("key-1"), anyString())).willReturn(null);
        given(stockStore.reserve(PRODUCT_ID, 1))
                .willReturn(new SeckillStockStore.Reservation(
                        SeckillStockStore.ReservationStatus.MISSING, 0L));

        assertThatThrownBy(() -> service.admit("key-1", request(1)))
                .isInstanceOfSatisfying(BizException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));

        verify(idempotencyStore).release("key-1");
        verifyNoInteractions(publisher);
        assertThat(attempts("error")).isEqualTo(1.0d);
    }

    @Test
    void publishFailureRestoresStockAndDeletesClaimAndState() {
        given(lockGuard.tryLock(PRODUCT_ID)).willReturn(lockHandle);
        given(idempotencyStore.claim(eq("key-1"), anyString())).willReturn(null);
        given(stockStore.reserve(PRODUCT_ID, 1))
                .willReturn(new SeckillStockStore.Reservation(
                        SeckillStockStore.ReservationStatus.RESERVED, 9L));
        org.mockito.BDDMockito.willThrow(new IllegalStateException("broker down"))
                .given(publisher).publish(any());

        assertThatThrownBy(() -> service.admit("key-1", request(1)))
                .isInstanceOfSatisfying(BizException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));

        ArgumentCaptor<SeckillRequest> state = ArgumentCaptor.forClass(SeckillRequest.class);
        verify(requestStore).save(state.capture());
        verify(requestStore).delete(state.getValue().requestId());
        verify(stockStore).restore(PRODUCT_ID, 1);
        verify(idempotencyStore).release("key-1");
        verify(lockHandle).close();
        assertThat(attempts("error")).isEqualTo(1.0d);
        assertThat(attempts("accepted")).isZero();
    }

    @Test
    void stockReadFailureRestoresNothingButReleasesTheClaim() {
        given(lockGuard.tryLock(PRODUCT_ID)).willReturn(lockHandle);
        given(idempotencyStore.claim(eq("key-1"), anyString())).willReturn(null);
        given(stockStore.reserve(PRODUCT_ID, 1))
                .willThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> service.admit("key-1", request(1)))
                .isInstanceOfSatisfying(BizException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));

        verify(idempotencyStore).release("key-1");
        verify(stockStore, never()).restore(anyLong(), anyInt());
        verifyNoInteractions(publisher);
        assertThat(attempts("error")).isEqualTo(1.0d);
    }

    @Test
    void invalidQuantityIsBadRequestBeforeAnyRedisCall() {
        assertThatThrownBy(() -> service.admit("key-1", request(0)))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(CommonErrorCode.INVALID_ARGUMENT);
                    assertThat(exception.getMessage()).isEqualTo("quantity must be positive");
                });

        verifyNoInteractions(lockGuard, idempotencyStore, stockStore, requestStore, publisher);
        assertThat(attempts("invalid")).isEqualTo(1.0d);
    }

    @Test
    void blankIdempotencyKeyIsBadRequest() {
        assertThatThrownBy(() -> service.admit("  ", request(1)))
                .isInstanceOfSatisfying(BizException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.INVALID_ARGUMENT));

        verifyNoInteractions(lockGuard, idempotencyStore, stockStore, requestStore, publisher);
        assertThat(attempts("invalid")).isEqualTo(1.0d);
    }

    @Test
    void syncToggleDelegatesToTheDurableOrderPathWithoutMq() {
        SeckillAdmissionService syncService = new SeckillAdmissionService(
                properties(false),
                lockGuard,
                idempotencyStore,
                stockStore,
                requestStore,
                publisher,
                orderApplicationService,
                new SeckillMetrics(meterRegistry));
        Order order = new Order(7L, "order-no-7", "u1", PRODUCT_ID, 1,
                OrderStatus.CREATED, "key-1", Instant.parse("2026-10-26T08:00:00Z"));
        given(orderApplicationService.create(any())).willReturn(order);

        SeckillResponse response = syncService.admit("key-1", request(1));

        assertThat(response.status()).isEqualTo("CREATED");
        assertThat(response.orderId()).isEqualTo(7L);
        assertThat(response.requestId()).isNull();
        verifyNoInteractions(lockGuard, idempotencyStore, stockStore, requestStore, publisher);
        assertThat(attempts("accepted")).isEqualTo(1.0d);
    }

    private static CreateSeckillOrderRequest request(int quantity) {
        return new CreateSeckillOrderRequest("u1", PRODUCT_ID, quantity);
    }

    private double attempts(String result) {
        Counter counter = meterRegistry.find("seckill_attempts").tag("result", result).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static SeckillProperties properties(boolean asyncEnabled) {
        SeckillProperties properties = new SeckillProperties();
        properties.setAsyncEnabled(asyncEnabled);
        properties.getLock().setKeyPrefix("lock:test:");
        properties.getLock().setWaitMs(100L);
        properties.getLock().setLeaseMs(1000L);
        properties.getStock().setKeyPrefix("test:stock:");
        properties.getStock().setSeedOnStartup(false);
        properties.getStock().setLuaPath("lua/seckill_stock.lua");
        properties.getRequest().setKeyPrefix("test:request:");
        properties.getRequest().setTtlSeconds(60L);
        properties.getIdempotency().setKeyPrefix("test:idem:");
        properties.getIdempotency().setTtlSeconds(60L);
        properties.getMq().setExchange("test.exchange");
        properties.getMq().setQueue("test.queue");
        properties.getMq().setRoutingKey("test.routing");
        properties.getConsumer().setDelayMs(0L);
        properties.getRedisson().setConnectTimeoutMs(1000);
        properties.getRedisson().setTimeoutMs(1000);
        properties.getRedisson().setRetryAttempts(1);
        properties.getRedisson().setRetryIntervalMs(100);
        properties.getRedisson().setConnectionMinimumIdleSize(1);
        properties.getRedisson().setConnectionPoolSize(2);
        properties.getRedisson().setLazyInitialization(true);
        return properties;
    }
}
