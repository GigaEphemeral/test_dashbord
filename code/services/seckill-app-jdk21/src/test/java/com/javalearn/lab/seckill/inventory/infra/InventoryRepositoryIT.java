package com.javalearn.lab.seckill.inventory.infra;

import com.javalearn.lab.seckill.MySqlRabbitIntegrationTestSupport;
import com.javalearn.lab.seckill.inventory.domain.InventoryRepository;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import com.javalearn.lab.seckill.order.domain.OrderStatus;
import com.javalearn.lab.seckill.order.error.DuplicateOrderKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inventory/order persistence against MySQL 8.4.4.
 *
 * <p>Isolation: the container uses MySQL's default REPEATABLE READ. The
 * oversell proof does not depend on snapshot reads: every reservation is a
 * single conditional {@code UPDATE ... WHERE available >= quantity}, which
 * InnoDB evaluates with a current (locking) read. Concurrent updaters queue on
 * the product row and the stock can never go negative.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=test-1"
        })
class InventoryRepositoryIT extends MySqlRabbitIntegrationTestSupport {

    private static final long PRODUCT_ID = 1L;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("UPDATE inventory_stock SET available = 100, reserved = 0");
        jdbcTemplate.update("DELETE FROM order_order");
    }

    @Test
    void flywaySeedCreatesOneInventoryRowPerProduct() {
        Integer inventoryRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_stock", Integer.class);

        assertThat(inventoryRows).isEqualTo(20);
        assertThat(available(PRODUCT_ID)).isEqualTo(100);
    }

    @Test
    void reservesStockAndDecrementsAvailable() {
        setAvailable(PRODUCT_ID, 20);

        assertThat(inventoryRepository.reserve(PRODUCT_ID, 5)).isTrue();

        assertThat(available(PRODUCT_ID)).isEqualTo(15);
    }

    @Test
    void refusesReservationWhenStockIsInsufficient() {
        setAvailable(PRODUCT_ID, 20);

        assertThat(inventoryRepository.reserve(PRODUCT_ID, 21)).isFalse();
        assertThat(available(PRODUCT_ID)).isEqualTo(20);

        assertThat(inventoryRepository.reserve(PRODUCT_ID, 20)).isTrue();
        assertThat(inventoryRepository.reserve(PRODUCT_ID, 1)).isFalse();
        assertThat(available(PRODUCT_ID)).isZero();
    }

    @Test
    void refusesReservationForUnknownProduct() {
        assertThat(inventoryRepository.reserve(999999L, 1)).isFalse();
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        int attempts = 50;
        setAvailable(PRODUCT_ID, 20);

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return inventoryRepository.reserve(PRODUCT_ID, 1);
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(60, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }

        assertThat(available(PRODUCT_ID)).isZero();
        Integer minimum = jdbcTemplate.queryForObject(
                "SELECT MIN(available) FROM inventory_stock", Integer.class);
        assertThat(minimum).isNotNull().isGreaterThanOrEqualTo(0);
    }

    @Test
    void rejectsDuplicateOrderNumber() {
        orderRepository.save(newOrder("order-no-duplicate", "key-a"));

        assertThatThrownBy(() -> orderRepository.save(newOrder("order-no-duplicate", "key-b")))
                .isInstanceOf(DuplicateOrderKeyException.class);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateIdempotencyKey() {
        orderRepository.save(newOrder("order-no-a", "idempotency-duplicate"));

        assertThatThrownBy(() -> orderRepository.save(newOrder("order-no-b", "idempotency-duplicate")))
                .isInstanceOf(DuplicateOrderKeyException.class);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void findsOrderByIdAndIdempotencyKey() {
        Order saved = orderRepository.save(newOrder("order-no-lookup", "key-lookup"));

        assertThat(saved.getId()).isPositive();
        Order byId = orderRepository.findById(saved.getId()).orElseThrow();
        Order byKey = orderRepository.findByIdempotencyKey("key-lookup").orElseThrow();

        assertThat(byId.getId()).isEqualTo(saved.getId());
        assertThat(byKey.getId()).isEqualTo(saved.getId());
        assertThat(byKey.getOrderNo()).isEqualTo("order-no-lookup");
        assertThat(byKey.getUserId()).isEqualTo("u1");
        assertThat(byKey.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(byKey.getQuantity()).isEqualTo(1);
        assertThat(byKey.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(byKey.getCreatedAt()).isNotNull();
        assertThat(orderRepository.findByIdempotencyKey("missing-key")).isEmpty();
    }

    private void setAvailable(long productId, int available) {
        int updated = jdbcTemplate.update(
                "UPDATE inventory_stock SET available = ?, reserved = 0 WHERE product_id = ?",
                available, productId);
        assertThat(updated).isEqualTo(1);
    }

    private int available(long productId) {
        Integer available = jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = ?",
                Integer.class, productId);
        return available == null ? -1 : available;
    }

    private long orderCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_order", Long.class);
        return count == null ? 0L : count;
    }

    private static Order newOrder(String orderNo, String idempotencyKey) {
        return new Order(0L, orderNo, "u1", PRODUCT_ID, 1, OrderStatus.CREATED,
                idempotencyKey, Instant.now());
    }
}
