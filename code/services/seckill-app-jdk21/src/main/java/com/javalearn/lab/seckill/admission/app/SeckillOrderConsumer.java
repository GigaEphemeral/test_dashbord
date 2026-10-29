package com.javalearn.lab.seckill.admission.app;

import com.javalearn.lab.seckill.admission.config.SeckillProperties;
import com.javalearn.lab.seckill.admission.domain.SeckillOrderMessage;
import com.javalearn.lab.seckill.order.api.CreateOrderCommand;
import com.javalearn.lab.seckill.order.app.OrderApplicationService;
import com.javalearn.lab.seckill.order.app.OrderQueryService;
import com.javalearn.lab.seckill.order.app.OrderRequestSource;
import com.javalearn.lab.seckill.order.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Paced consumer of accepted seckill requests.
 *
 * <p>The durable order is created exclusively through
 * {@link OrderApplicationService}, i.e. the same database atomic-stock and
 * unique-idempotency path as {@code POST /api/orders}. The Redis stock was
 * already reserved at admission, so the consumer never decrements Redis stock
 * again; on failure it restores that reservation best-effort and marks the
 * request FAILED.</p>
 *
 * <p>{@code consumer.delay-ms} is applied before handling to simulate paced
 * processing and to make queue depth visible in demos. It is fully
 * configurable.</p>
 *
 * <p>Since B4-01 the work runs on the bounded business executor
 * ({@code app.business-pool.*}) instead of the listener thread: the listener
 * submits and waits, so the lab can set core/max/queue/rejection and watch
 * {@code executor_active_threads}/{@code executor_queued_tasks}/
 * {@code executor_rejected_total} move under load. A refused task is treated
 * exactly like a processing failure - the request is marked FAILED and the
 * Redis reservation restored - so no accepted request is silently dropped.</p>
 */
public class SeckillOrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderConsumer.class);

    private final SeckillProperties properties;
    private final SeckillRequestStore requestStore;
    private final SeckillStockStore stockStore;
    private final OrderApplicationService orderApplicationService;
    private final OrderQueryService orderQueryService;
    private final SeckillMetrics metrics;
    private final ThreadPoolExecutor businessExecutor;

    public SeckillOrderConsumer(SeckillProperties properties,
                                SeckillRequestStore requestStore,
                                SeckillStockStore stockStore,
                                OrderApplicationService orderApplicationService,
                                OrderQueryService orderQueryService,
                                SeckillMetrics metrics,
                                ThreadPoolExecutor businessExecutor) {
        this.properties = properties;
        this.requestStore = requestStore;
        this.stockStore = stockStore;
        this.orderApplicationService = orderApplicationService;
        this.orderQueryService = orderQueryService;
        this.metrics = metrics;
        this.businessExecutor = businessExecutor;
    }

    @RabbitListener(queues = "${app.seckill.mq.queue}")
    public void onMessage(SeckillOrderMessage message) {
        boolean processed;
        try {
            // The wait is intentional: the broker acknowledges the delivery only
            // after the bounded pool finished (or refused) the work, so a
            // saturated pool is visible in executor_queued_tasks instead of
            // silently becoming unbounded listener concurrency.
            Future<Boolean> future = businessExecutor.submit(() -> {
                applyProcessingDelay();
                return process(message);
            });
            processed = future.get();
        } catch (RejectedExecutionException rejected) {
            log.warn("Business executor refused the seckill request {}: {}",
                    message.requestId(), rejected.toString());
            recordFailure(message, rejected);
            throw new AmqpRejectAndDontRequeueException(
                    "Seckill order request rejected by the business pool: " + message.requestId(),
                    rejected);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AmqpRejectAndDontRequeueException(
                    "Interrupted while waiting for the seckill order request "
                            + message.requestId(), interrupted);
        } catch (ExecutionException failure) {
            // process() handles its own failures; this is an unexpected Error.
            log.error("Business executor failed while handling seckill request {}",
                    message.requestId(), failure.getCause());
            processed = false;
        }
        if (!processed) {
            // The request is already marked FAILED and the Redis reservation was
            // restored; reject without requeue so the broker does not loop.
            throw new AmqpRejectAndDontRequeueException(
                    "Seckill order request failed: " + message.requestId());
        }
    }

    /**
     * Handles one message.
     *
     * @return {@code true} when the order exists (created now or previously),
     *         {@code false} after a failure that was recorded and compensated
     */
    public boolean process(SeckillOrderMessage message) {
        Optional<Order> existing = findExistingOrder(message.idempotencyKey());
        try {
            Order order = orderApplicationService.create(new CreateOrderCommand(
                    message.userId(),
                    message.productId(),
                    message.quantity(),
                    message.idempotencyKey()),
                    // SECKILL scope: duplicate MQ deliveries are counted by
                    // order_consumer_processed{result="duplicate"}, never by
                    // idempotent_reject (Task 18 brief: sync /api/orders only).
                    OrderRequestSource.SECKILL);
            recordCreated(message, order);
            metrics.consumerProcessed(existing.isPresent()
                    ? SeckillMetrics.CONSUMER_DUPLICATE
                    : SeckillMetrics.CONSUMER_CREATED);
            log.info("Seckill order {} for request {} status={} (duplicate={})",
                    order.getId(), message.requestId(), order.getStatus(), existing.isPresent());
            return true;
        } catch (RuntimeException failure) {
            Optional<Order> completed = existing.isPresent()
                    ? existing
                    : findExistingOrder(message.idempotencyKey());
            if (completed.isPresent()) {
                // The durable order already exists (possibly created by a
                // concurrent duplicate delivery); the reservation is
                // legitimately consumed. Never restore Redis stock here.
                recordCreated(message, completed.get());
                metrics.consumerProcessed(SeckillMetrics.CONSUMER_DUPLICATE);
                log.warn("Seckill order already exists for request {}; delivery failed but the "
                        + "request is complete: {}", message.requestId(), failure.toString());
                return true;
            }
            recordFailure(message, failure);
            metrics.consumerProcessed(SeckillMetrics.CONSUMER_FAILED);
            return false;
        }
    }

    private Optional<Order> findExistingOrder(String idempotencyKey) {
        try {
            return orderQueryService.findByIdempotencyKey(idempotencyKey);
        } catch (RuntimeException lookupFailure) {
            // The lookup is only a metrics/compensation hint; the database
            // unique key is the authority for duplicate delivery.
            log.warn("Duplicate pre-check failed for seckill request; continuing", lookupFailure);
            return Optional.empty();
        }
    }

    private void recordCreated(SeckillOrderMessage message, Order order) {
        try {
            requestStore.markCreated(message.requestId(), order.getId());
        } catch (RuntimeException stateFailure) {
            // The durable order exists; only the polling state could not be
            // updated. Never restore stock here.
            log.warn("Seckill request state could not be marked CREATED for {}",
                    message.requestId(), stateFailure);
        }
    }

    private void recordFailure(SeckillOrderMessage message, RuntimeException failure) {
        try {
            boolean restored = stockStore.restore(message.productId(), message.quantity());
            if (!restored) {
                log.warn("Seckill stock key for product {} was absent during consumer rollback",
                        message.productId());
            }
        } catch (RuntimeException restoreFailure) {
            log.warn("Best-effort seckill stock restore failed for product {}",
                    message.productId(), restoreFailure);
        }
        try {
            requestStore.markFailed(message.requestId(), failure.getMessage());
        } catch (RuntimeException stateFailure) {
            log.warn("Seckill request state could not be marked FAILED for {}",
                    message.requestId(), stateFailure);
        }
        log.error("Seckill order creation failed for request {}; stock restored best-effort",
                message.requestId(), failure);
    }

    private void applyProcessingDelay() {
        long delayMs = properties.getConsumer().getDelayMs();
        if (delayMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AmqpRejectAndDontRequeueException(
                    "Interrupted while pacing seckill consumer", interrupted);
        }
    }
}
