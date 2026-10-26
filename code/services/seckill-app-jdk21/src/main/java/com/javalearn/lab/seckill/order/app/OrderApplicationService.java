package com.javalearn.lab.seckill.order.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.order.api.CreateOrderCommand;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.error.DuplicateOrderKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Non-transactional application wrapper around the transactional order flow.
 *
 * <p>Two concurrent requests with the same {@code Idempotency-Key} both reserve
 * stock and both try to insert; the database unique key lets exactly one win.
 * The loser sees {@link DuplicateOrderKeyException}, its transaction (including
 * the stock update) has rolled back, and this wrapper re-reads the winner's
 * committed row in a fresh read transaction. Up to ten ~50&nbsp;ms attempts
 * cover the short window between duplicate detection and commit visibility.</p>
 */
public class OrderApplicationService {

    private static final Logger log = LoggerFactory.getLogger(OrderApplicationService.class);

    private static final int MAX_RETRIES = 10;
    private static final long RETRY_SLEEP_MILLIS = 50L;

    private final OrderTransactionService orderTransactionService;
    private final OrderQueryService orderQueryService;

    public OrderApplicationService(OrderTransactionService orderTransactionService,
                                   OrderQueryService orderQueryService) {
        this.orderTransactionService = Objects.requireNonNull(
                orderTransactionService, "orderTransactionService");
        this.orderQueryService = Objects.requireNonNull(orderQueryService, "orderQueryService");
    }

    public Order create(CreateOrderCommand command) {
        Objects.requireNonNull(command, "command");
        try {
            return orderTransactionService.create(command);
        } catch (DuplicateOrderKeyException duplicate) {
            log.info("Duplicate idempotency key '{}' detected; returning the winning order",
                    command.getIdempotencyKey());
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                Optional<Order> existing = orderQueryService.findByIdempotencyKey(
                        command.getIdempotencyKey());
                if (existing.isPresent()) {
                    return existing.get();
                }
                sleepBeforeRetry();
            }
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "Idempotent order not visible yet", duplicate);
        }
    }

    public Order get(long id) {
        return orderQueryService.get(id);
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_SLEEP_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "Interrupted while waiting for the idempotent order", interrupted);
        }
    }
}
