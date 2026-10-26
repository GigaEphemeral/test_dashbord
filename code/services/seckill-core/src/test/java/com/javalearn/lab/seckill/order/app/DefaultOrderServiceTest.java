package com.javalearn.lab.seckill.order.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.inventory.domain.InventoryRepository;
import com.javalearn.lab.seckill.order.api.CreateOrderCommand;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import com.javalearn.lab.seckill.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD coverage for the framework-neutral order use case. The fakes use atomic
 * counters so "reserved once" and "saved once" are observable, mirroring the
 * real atomic stock update.
 */
class DefaultOrderServiceTest {

    private final FakeInventoryRepository inventory = new FakeInventoryRepository(10);
    private final FakeOrderRepository orders = new FakeOrderRepository();
    private final DefaultOrderService service = new DefaultOrderService(orders, inventory);

    @Test
    void createsOrderWithExactFieldsAndSavesOnce() {
        Instant before = Instant.now();

        Order created = service.create(command("u1", 1L, 2, "key-1"));

        assertTrue(created.getId() > 0);
        assertNotNull(created.getOrderNo());
        assertFalse(created.getOrderNo().trim().isEmpty());
        assertEquals("u1", created.getUserId());
        assertEquals(1L, created.getProductId());
        assertEquals(2, created.getQuantity());
        assertEquals(OrderStatus.CREATED, created.getStatus());
        assertEquals("key-1", created.getIdempotencyKey());
        assertFalse(created.getCreatedAt().isBefore(before.minusSeconds(1)));
        assertFalse(created.getCreatedAt().isAfter(Instant.now().plusSeconds(1)));
        assertEquals(1, orders.saveCalls.get());
        assertEquals(1, inventory.reserveCalls.get());
        assertEquals(8, inventory.available());
    }

    @Test
    void repeatedKeyReturnsExistingOrderWithoutReservingAgain() {
        Order first = service.create(command("u1", 1L, 2, "key-1"));

        Order replay = service.create(command("u1", 1L, 2, "key-1"));

        assertEquals(first.getId(), replay.getId());
        assertEquals(first.getOrderNo(), replay.getOrderNo());
        assertEquals(1, orders.saveCalls.get());
        assertEquals(1, inventory.reserveCalls.get());
        assertEquals(8, inventory.available());
    }

    @Test
    void soldOutThrowsConflictAndSavesNothing() {
        inventory.setAvailable(1);

        BizException exception = assertThrows(BizException.class,
                () -> service.create(command("u1", 1L, 2, "key-1")));

        assertEquals(CommonErrorCode.CONFLICT, exception.getErrorCode());
        assertEquals("Sold out", exception.getMessage());
        assertEquals(0, orders.saveCalls.get());
        assertEquals(1, inventory.available());
    }

    @Test
    void rejectsZeroQuantityBeforeTouchingRepositories() {
        assertInvalid(command("u1", 1L, 0, "key-1"), "quantity must be positive");
    }

    @Test
    void rejectsNegativeQuantityBeforeTouchingRepositories() {
        assertInvalid(command("u1", 1L, -3, "key-1"), "quantity must be positive");
    }

    @Test
    void rejectsBlankIdempotencyKeyBeforeTouchingRepositories() {
        assertInvalid(command("u1", 1L, 1, "   "), "Idempotency-Key is required");
        assertInvalid(command("u1", 1L, 1, null), "Idempotency-Key is required");
    }

    @Test
    void rejectsBlankUserIdBeforeTouchingRepositories() {
        assertInvalid(command("  ", 1L, 1, "key-1"), "userId is required");
        assertInvalid(command(null, 1L, 1, "key-1"), "userId is required");
    }

    @Test
    void rejectsNonPositiveProductIdBeforeTouchingRepositories() {
        assertInvalid(command("u1", 0L, 1, "key-1"), "productId must be positive");
    }

    @Test
    void getReturnsPersistedOrder() {
        Order created = service.create(command("u1", 1L, 1, "key-1"));

        assertEquals(created.getOrderNo(), service.get(created.getId()).getOrderNo());
    }

    @Test
    void getMissingOrderThrowsNotFound() {
        BizException exception = assertThrows(BizException.class, () -> service.get(999L));

        assertEquals(CommonErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    private void assertInvalid(CreateOrderCommand command, String expectedMessage) {
        BizException exception = assertThrows(BizException.class, () -> service.create(command));

        assertEquals(CommonErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
        assertEquals(expectedMessage, exception.getMessage());
        assertEquals(0, orders.saveCalls.get());
        assertEquals(0, inventory.reserveCalls.get());
    }

    private static CreateOrderCommand command(String userId, long productId, int quantity, String key) {
        return new CreateOrderCommand(userId, productId, quantity, key);
    }

    private static final class FakeInventoryRepository implements InventoryRepository {

        private final AtomicInteger available;
        private final AtomicInteger reserveCalls = new AtomicInteger();

        FakeInventoryRepository(int initialAvailable) {
            this.available = new AtomicInteger(initialAvailable);
        }

        @Override
        public boolean reserve(long productId, int quantity) {
            reserveCalls.incrementAndGet();
            while (true) {
                int current = available.get();
                if (current < quantity) {
                    return false;
                }
                if (available.compareAndSet(current, current - quantity)) {
                    return true;
                }
            }
        }

        int available() {
            return available.get();
        }

        void setAvailable(int value) {
            available.set(value);
        }
    }

    private static final class FakeOrderRepository implements OrderRepository {

        private final Map<Long, Order> byId = new HashMap<>();
        private final Map<String, Order> byKey = new HashMap<>();
        private final AtomicLong ids = new AtomicLong();
        private final AtomicInteger saveCalls = new AtomicInteger();

        @Override
        public Order save(Order order) {
            saveCalls.incrementAndGet();
            long id = order.getId() == 0 ? ids.incrementAndGet() : order.getId();
            Order saved = order.withId(id);
            byId.put(id, saved);
            byKey.put(saved.getIdempotencyKey(), saved);
            return saved;
        }

        @Override
        public Optional<Order> findById(long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byKey.get(idempotencyKey));
        }
    }
}
