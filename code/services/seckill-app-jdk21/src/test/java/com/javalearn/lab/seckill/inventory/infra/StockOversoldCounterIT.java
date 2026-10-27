package com.javalearn.lab.seckill.inventory.infra;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.seckill.MySqlRabbitIntegrationTestSupport;
import com.javalearn.lab.seckill.inventory.domain.InventoryRepository;
import com.javalearn.lab.seckill.order.api.CreateOrderCommand;
import com.javalearn.lab.seckill.order.app.OrderApplicationService;
import com.javalearn.lab.seckill.order.app.OrderRequestSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression for {@code stock_oversold_total} against real MySQL 8.4.4.
 *
 * <p>These scenarios do not stub the affected-row count: the conditional
 * {@code UPDATE ... WHERE available >= :quantity} genuinely matches zero rows
 * when the stock is insufficient, which is the exact teaching definition of a
 * prevented oversell attempt. The counter must rise by one per such attempt,
 * regardless of which business path (here: the synchronous order path) hit
 * the guard, and must stay untouched when the reservation succeeds.</p>
 *
 * <p>MeterRegistry counts are cumulative inside the shared cached context, so
 * every assertion measures a delta around its own action.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=test-1"
        })
class StockOversoldCounterIT extends MySqlRabbitIntegrationTestSupport {

    private static final long PRODUCT_ID = 1L;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM order_order");
    }

    @Test
    void conditionalUpdateMatchingZeroRowsIncrementsStockOversold() {
        setAvailable(PRODUCT_ID, 2);
        double before = stockOversold();

        // 3 units requested against 2 available: the UPDATE matches zero rows.
        assertThat(inventoryRepository.reserve(PRODUCT_ID, 3)).isFalse();
        assertThat(stockOversold()).isEqualTo(before + 1.0d);

        // A reservation that succeeds is not a prevented oversell.
        assertThat(inventoryRepository.reserve(PRODUCT_ID, 2)).isTrue();
        assertThat(stockOversold()).isEqualTo(before + 1.0d);

        // The now-empty row refuses the next attempt: one more count.
        assertThat(inventoryRepository.reserve(PRODUCT_ID, 1)).isFalse();
        assertThat(stockOversold()).isEqualTo(before + 2.0d);

        assertThat(available(PRODUCT_ID)).isZero();
    }

    @Test
    void syncOrderPathCountsOnePreventedOversellPerBlockedAttempt() {
        setAvailable(PRODUCT_ID, 0);
        double before = stockOversold();

        assertThatThrownBy(() -> orderApplicationService.create(
                new CreateOrderCommand("oversold-user", PRODUCT_ID, 1, "oversold-sync-key"),
                OrderRequestSource.SYNC_API))
                .isInstanceOf(BizException.class);

        assertThat(stockOversold()).isEqualTo(before + 1.0d);

        // Retrying the blocked purchase counts the second attempt too; the
        // idempotent replay of a created order never re-reserves stock, but a
        // rejected attempt has no order to replay.
        assertThatThrownBy(() -> orderApplicationService.create(
                new CreateOrderCommand("oversold-user", PRODUCT_ID, 1, "oversold-sync-key-2"),
                OrderRequestSource.SYNC_API))
                .isInstanceOf(BizException.class);
        assertThat(stockOversold()).isEqualTo(before + 2.0d);
    }

    @Test
    void successfulSyncOrderLeavesStockOversoldUntouched() {
        setAvailable(PRODUCT_ID, 5);
        double before = stockOversold();

        orderApplicationService.create(
                new CreateOrderCommand("oversold-user", PRODUCT_ID, 5, "oversold-ok-key"),
                OrderRequestSource.SYNC_API);

        assertThat(stockOversold()).isEqualTo(before);
        assertThat(available(PRODUCT_ID)).isZero();
    }

    private void setAvailable(long productId, int available) {
        int updated = jdbcTemplate.update(
                "UPDATE inventory_stock SET available = ?, reserved = 0 WHERE product_id = ?",
                available, productId);
        assertThat(updated).isEqualTo(1);
    }

    private int available(long productId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = ?",
                Integer.class, productId);
        return value == null ? -1 : value;
    }

    private double stockOversold() {
        Counter counter = meterRegistry
                .find(JpaInventoryRepositoryAdapter.STOCK_OVERSOLD_METRIC)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }
}
