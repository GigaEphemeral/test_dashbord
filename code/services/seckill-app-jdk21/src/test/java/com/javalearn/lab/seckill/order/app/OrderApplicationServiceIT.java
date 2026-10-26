package com.javalearn.lab.seckill.order.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.seckill.MySqlIntegrationTestSupport;
import com.javalearn.lab.seckill.order.api.CreateOrderCommand;
import com.javalearn.lab.seckill.order.domain.Order;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end application-service concurrency tests against MySQL 8.4.4.
 *
 * <p>Isolation: MySQL default REPEATABLE READ. Correctness never relies on a
 * snapshot read for stock: {@code UPDATE ... WHERE available >= quantity} is a
 * locking current read. The only snapshot-sensitive part is idempotency, and
 * that is backed by the {@code uk_order_idempotency} unique key plus a retry in
 * a fresh transaction, so a concurrent duplicate can never create two rows.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=test-1"
        })
class OrderApplicationServiceIT extends MySqlIntegrationTestSupport {

    private static final long PRODUCT_ID = 1L;

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        int updated = jdbcTemplate.update(
                "UPDATE inventory_stock SET available = 20, reserved = 0 WHERE product_id = ?",
                PRODUCT_ID);
        assertThat(updated).isEqualTo(1);
        jdbcTemplate.update("DELETE FROM order_order");
    }

    @Test
    void fortyConcurrentDifferentKeysReserveExactlyTwenty() throws Exception {
        List<AttemptResult> results = runConcurrently(40,
                index -> new CreateOrderCommand("user-" + index, PRODUCT_ID, 1, "diff-key-" + index));

        List<AttemptResult> successes = results.stream()
                .filter(AttemptResult::succeeded)
                .collect(Collectors.toList());
        List<AttemptResult> conflicts = results.stream()
                .filter(result -> "40901".equals(result.errorCode()))
                .collect(Collectors.toList());
        List<AttemptResult> unexpected = results.stream()
                .filter(result -> result.unexpected() != null)
                .collect(Collectors.toList());

        assertThat(unexpected).isEmpty();
        assertThat(successes).hasSize(20);
        assertThat(conflicts).hasSize(20);
        assertThat(orderCount()).isEqualTo(20);
        assertThat(available(PRODUCT_ID)).isZero();
        assertThat(minimumAvailable()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void tenConcurrentSameKeyCreateExactlyOneOrder() throws Exception {
        List<AttemptResult> results = runConcurrently(10,
                index -> new CreateOrderCommand("u1", PRODUCT_ID, 1, "same-key"));

        assertThat(results).filteredOn(result -> result.unexpected() != null).isEmpty();
        assertThat(results).filteredOn(AttemptResult::succeeded).hasSize(10);

        Set<Long> orderIds = results.stream()
                .filter(AttemptResult::succeeded)
                .map(result -> result.order().getId())
                .collect(Collectors.toSet());

        assertThat(orderIds).hasSize(1);
        assertThat(orderIds.iterator().next()).isPositive();
        assertThat(orderCount()).isEqualTo(1);
        // Nine losing transactions reserved stock and then rolled back.
        assertThat(available(PRODUCT_ID)).isEqualTo(19);
    }

    @Test
    void sequentialRepeatReturnsSameOrderAndDoesNotDecrementTwice() {
        CreateOrderCommand command = new CreateOrderCommand("u1", PRODUCT_ID, 2, "sequential-key");
        double counterBefore = ordersCreatedCount();

        Order first = orderApplicationService.create(command);
        double counterAfterCreate = ordersCreatedCount();
        Order replay = orderApplicationService.create(command);
        double counterAfterReplay = ordersCreatedCount();

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getOrderNo()).isEqualTo(first.getOrderNo());
        assertThat(orderCount()).isEqualTo(1);
        assertThat(available(PRODUCT_ID)).isEqualTo(18);
        assertThat(counterAfterCreate).isEqualTo(counterBefore + 1.0d);
        assertThat(counterAfterReplay).isEqualTo(counterAfterCreate);
    }

    @Test
    void getReturnsCreatedOrder() {
        Order created = orderApplicationService.create(
                new CreateOrderCommand("u1", PRODUCT_ID, 1, "get-key"));

        Order found = orderApplicationService.get(created.getId());

        assertThat(found.getOrderNo()).isEqualTo(created.getOrderNo());
        assertThat(found.getStatus().name()).isEqualTo("CREATED");
    }

    private List<AttemptResult> runConcurrently(int count, IntFunction<CreateOrderCommand> commandFactory)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AttemptResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                CreateOrderCommand command = commandFactory.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new AttemptResult(orderApplicationService.create(command), null, null);
                    } catch (BizException exception) {
                        return new AttemptResult(null, exception.getErrorCode().code(), null);
                    } catch (Throwable throwable) {
                        return new AttemptResult(null, null, throwable);
                    }
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<AttemptResult> results = new ArrayList<>();
            for (Future<AttemptResult> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private double ordersCreatedCount() {
        return meterRegistry.get("orders_created_total").counter().count();
    }

    private int available(long productId) {
        Integer available = jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = ?",
                Integer.class, productId);
        return available == null ? -1 : available;
    }

    private int minimumAvailable() {
        Integer minimum = jdbcTemplate.queryForObject(
                "SELECT MIN(available) FROM inventory_stock", Integer.class);
        return minimum == null ? -1 : minimum;
    }

    private long orderCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_order", Long.class);
        return count == null ? 0L : count;
    }

    private record AttemptResult(Order order, String errorCode, Throwable unexpected) {

        boolean succeeded() {
            return order != null;
        }
    }
}
