package com.javalearn.lab.seckill.admission.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.BusinessException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.admission.api.CreateSeckillOrderRequest;
import com.javalearn.lab.seckill.admission.api.SeckillRequestView;
import com.javalearn.lab.seckill.admission.api.SeckillResponse;
import com.javalearn.lab.seckill.admission.domain.SeckillOrderMessage;
import com.javalearn.lab.seckill.admission.domain.SeckillRequest;
import com.javalearn.lab.seckill.lab.toggle.LabToggle;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import com.javalearn.lab.seckill.order.api.CreateOrderCommand;
import com.javalearn.lab.seckill.order.app.OrderApplicationService;
import com.javalearn.lab.seckill.order.app.OrderRequestSource;
import com.javalearn.lab.seckill.order.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * Async seckill admission.
 *
 * <pre>
 * validate -&gt; per-product Redisson lock -&gt; Redis idempotency claim
 *          -&gt; atomic Lua stock reservation -&gt; persist request state
 *          -&gt; RabbitMQ publish -&gt; 202
 * </pre>
 *
 * <p>Only the admission is asynchronous. The consumer delegates to the existing
 * {@link OrderApplicationService}, so the database still reserves stock and
 * enforces the unique idempotency key exactly once.</p>
 *
 * <p>With the {@code mq} runtime toggle off (or
 * {@code app.seckill.async-enabled=false} as its startup seed) the request goes
 * straight to the existing synchronous order path (no MQ, no Redis stock) for
 * the naive comparison demo.</p>
 *
 * <p>Task 17 adds per-instance runtime toggles around each protection:
 * {@code ratelimit}/{@code circuitbreaker} live in
 * {@link SeckillResilienceGuard}, {@code lock} and {@code idempotency} are
 * checked here, and {@code mq} selects the async or synchronous path. Every
 * toggle is per-instance; the chaos-controller will broadcast later.</p>
 */
public class SeckillAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(SeckillAdmissionService.class);

    private final SeckillLockGuard lockGuard;
    private final SeckillIdempotencyStore idempotencyStore;
    private final SeckillStockStore stockStore;
    private final SeckillRequestStore requestStore;
    private final SeckillMessagePublisher publisher;
    private final OrderApplicationService orderApplicationService;
    private final SeckillMetrics metrics;
    private final LabToggleService toggles;
    private final SeckillResilienceGuard resilienceGuard;

    public SeckillAdmissionService(SeckillLockGuard lockGuard,
                                   SeckillIdempotencyStore idempotencyStore,
                                   SeckillStockStore stockStore,
                                   SeckillRequestStore requestStore,
                                   SeckillMessagePublisher publisher,
                                   OrderApplicationService orderApplicationService,
                                   SeckillMetrics metrics,
                                   LabToggleService toggles,
                                   SeckillResilienceGuard resilienceGuard) {
        this.lockGuard = Objects.requireNonNull(lockGuard, "lockGuard");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
        this.stockStore = Objects.requireNonNull(stockStore, "stockStore");
        this.requestStore = Objects.requireNonNull(requestStore, "requestStore");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.orderApplicationService = Objects.requireNonNull(
                orderApplicationService, "orderApplicationService");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.toggles = Objects.requireNonNull(toggles, "toggles");
        this.resilienceGuard = Objects.requireNonNull(resilienceGuard, "resilienceGuard");
    }

    public boolean isAsyncEnabled() {
        return toggles.isEnabled(LabToggle.MQ);
    }

    /**
     * Accepts one seckill request according to the runtime mode.
     *
     * <p>Expected business outcomes are thrown as {@link BusinessException}
     * (ignored by the circuit breaker); every Redis/MQ/stock/publish/state
     * fault stays a plain {@code BizException(50301)} so the circuit breaker
     * counts it and real infrastructure outages can open the circuit.</p>
     *
     * @throws BizException {@code 40001} invalid input, {@code 40901} sold out
     *                      or lock timeout (as {@code BusinessException}),
     *                      {@code 42901} rate limited, {@code 50301} Redis/
     *                      RabbitMQ failure (plain {@code BizException})
     */
    public SeckillResponse admit(String idempotencyKey, CreateSeckillOrderRequest request) {
        validate(idempotencyKey, request);
        long startNanos = System.nanoTime();
        try {
            if (!toggles.isEnabled(LabToggle.MQ)) {
                SeckillResponse response = createSynchronously(idempotencyKey, request);
                metrics.attempt(SeckillMetrics.RESULT_ACCEPTED);
                return response;
            }
            // The fallback is passed directly (not through admit) so degradation
            // never re-enters rate limiting, the circuit breaker or the lock.
            return resilienceGuard.admitAsync(
                    () -> admitAsync(idempotencyKey, request),
                    () -> createSynchronously(idempotencyKey, request));
        } finally {
            metrics.recordAcceptNanos(System.nanoTime() - startNanos);
        }
    }

    public SeckillRequestView getRequest(String requestId) {
        return requestStore.find(requestId)
                .map(SeckillRequestView::from)
                .orElseThrow(() -> new BizException(
                        CommonErrorCode.NOT_FOUND, "Seckill request not found: " + requestId));
    }

    private SeckillResponse createSynchronously(String idempotencyKey,
                                                CreateSeckillOrderRequest request) {
        // SECKILL scope: the durable replay must not inflate idempotent_reject,
        // which the Task 18 brief reserves for the synchronous /api/orders path.
        Order order = orderApplicationService.create(new CreateOrderCommand(
                request.userId(),
                request.productId(),
                request.quantity(),
                idempotencyKey),
                OrderRequestSource.SECKILL);
        return new SeckillResponse(null, order.getStatus().name(), order.getId());
    }

    private SeckillResponse admitAsync(String idempotencyKey,
                                       CreateSeckillOrderRequest request) {
        long productId = request.productId();
        int quantity = request.quantity();

        // `lock` off: skip Redisson. The Redis Lua stock check is still atomic,
        // so the demo shows the extra serialization the lock buys, not oversell.
        SeckillLockGuard.LockHandle lock = null;
        if (toggles.isEnabled(LabToggle.LOCK)) {
            try {
                lock = lockGuard.tryLock(productId);
            } catch (RuntimeException lockFailure) {
                metrics.attempt(SeckillMetrics.RESULT_ERROR);
                log.error("Seckill lock failed for product {}", productId, lockFailure);
                throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                        "Seckill lock is unavailable", lockFailure);
            }
            if (lock == null) {
                metrics.attempt(SeckillMetrics.RESULT_LOCK_TIMEOUT);
                // Expected business outcome: a contention result, not a
                // backend fault. BusinessException keeps it out of the
                // circuit breaker's failure window.
                throw new BusinessException(CommonErrorCode.CONFLICT, "Seckill lock timeout");
            }
        }

        try {
            String requestId = UUID.randomUUID().toString();
            // `idempotency` off: skip the Redis claim entirely. The DB unique
            // key (uk_order_idempotency) remains the durable backstop; the
            // toggle demonstrates the app-layer gap (a second Redis stock hold,
            // one durable order), never a DB guarantee removal.
            if (toggles.isEnabled(LabToggle.IDEMPOTENCY)) {
                String claimedRequestId = idempotencyStore.claim(idempotencyKey, requestId);
                if (claimedRequestId != null) {
                    return replay(claimedRequestId);
                }
            }
            return acceptUnderLock(requestId, idempotencyKey, request, productId, quantity);
        } catch (BizException bizException) {
            throw bizException;
        } catch (RuntimeException unexpected) {
            metrics.attempt(SeckillMetrics.RESULT_ERROR);
            log.error("Seckill admission failed for product {}", productId, unexpected);
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "Seckill request could not be accepted", unexpected);
        } finally {
            if (lock != null) {
                lock.close();
            }
        }
    }

    private SeckillResponse replay(String requestId) {
        SeckillRequest existing;
        try {
            existing = requestStore.find(requestId).orElse(null);
        } catch (RuntimeException readFailure) {
            metrics.attempt(SeckillMetrics.RESULT_ERROR);
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "Seckill request state could not be read", readFailure);
        }
        if (existing == null) {
            // The first caller has claimed the idempotency key but has not
            // stored its state yet (or the state expired); the client can retry.
            metrics.attempt(SeckillMetrics.RESULT_ERROR);
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "Seckill request state is not available yet");
        }
        metrics.attempt(SeckillMetrics.RESULT_REPLAY);
        return SeckillResponse.from(existing);
    }

    private SeckillResponse acceptUnderLock(String requestId,
                                            String idempotencyKey,
                                            CreateSeckillOrderRequest request,
                                            long productId,
                                            int quantity) {
        SeckillStockStore.Reservation reservation;
        try {
            reservation = stockStore.reserve(productId, quantity);
        } catch (RuntimeException stockFailure) {
            releaseIdempotency(idempotencyKey);
            metrics.attempt(SeckillMetrics.RESULT_ERROR);
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "Seckill stock is unavailable", stockFailure);
        }

        if (reservation.status() == SeckillStockStore.ReservationStatus.MISSING) {
            releaseIdempotency(idempotencyKey);
            metrics.attempt(SeckillMetrics.RESULT_ERROR);
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "Seckill stock is not initialized for product " + productId);
        }
        if (reservation.status() == SeckillStockStore.ReservationStatus.SOLD_OUT) {
            releaseIdempotency(idempotencyKey);
            metrics.attempt(SeckillMetrics.RESULT_SOLD_OUT);
            // Expected business outcome, never a circuit breaker failure.
            throw new BusinessException(CommonErrorCode.CONFLICT, "Sold out");
        }

        SeckillRequest accepted = SeckillRequest.accepted(
                requestId, idempotencyKey, request.userId(), productId, quantity);
        try {
            requestStore.save(accepted);
            publisher.publish(SeckillOrderMessage.from(accepted));
        } catch (RuntimeException publishFailure) {
            rollbackReservation(requestId, idempotencyKey, productId, quantity);
            metrics.attempt(SeckillMetrics.RESULT_ERROR);
            log.error("Seckill publish failed for request {}; reservation rolled back", requestId,
                    publishFailure);
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "Seckill request could not be published", publishFailure);
        }

        metrics.attempt(SeckillMetrics.RESULT_ACCEPTED);
        log.info("Seckill request {} accepted for product {} (remaining stock {})",
                requestId, productId, reservation.remaining());
        return SeckillResponse.from(accepted);
    }

    private void rollbackReservation(String requestId,
                                     String idempotencyKey,
                                     long productId,
                                     int quantity) {
        try {
            boolean restored = stockStore.restore(productId, quantity);
            if (!restored) {
                log.warn("Seckill stock key for product {} was absent during rollback", productId);
            }
        } catch (RuntimeException restoreFailure) {
            log.warn("Best-effort seckill stock restore failed for product {}", productId,
                    restoreFailure);
        }
        try {
            requestStore.delete(requestId);
        } catch (RuntimeException deleteFailure) {
            log.warn("Best-effort seckill request delete failed for {}", requestId, deleteFailure);
        }
        releaseIdempotency(idempotencyKey);
    }

    private void releaseIdempotency(String idempotencyKey) {
        if (!toggles.isEnabled(LabToggle.IDEMPOTENCY)) {
            return;
        }
        try {
            idempotencyStore.release(idempotencyKey);
        } catch (RuntimeException releaseFailure) {
            log.warn("Best-effort idempotency release failed", releaseFailure);
        }
    }

    private void validate(String idempotencyKey, CreateSeckillOrderRequest request) {
        if (isBlank(idempotencyKey)) {
            invalid("Idempotency-Key is required");
        }
        if (request == null) {
            invalid("Request body is required");
        }
        if (isBlank(request.userId())) {
            invalid("userId is required");
        }
        if (request.productId() <= 0) {
            invalid("productId must be positive");
        }
        if (request.quantity() <= 0) {
            invalid("quantity must be positive");
        }
    }

    private void invalid(String message) {
        metrics.attempt(SeckillMetrics.RESULT_INVALID);
        throw new BizException(CommonErrorCode.INVALID_ARGUMENT, message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
