package com.javalearn.lab.seckill.inventory.infra;

import com.javalearn.lab.seckill.admission.app.SeckillMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Regression for {@code stock_oversold_total}: the counter must really
 * increment when the conditional stock update refuses an oversell.
 *
 * <p>Here the refusal is simulated one layer below the adapter: the mocked
 * {@link InventoryStockJpaRepository} returns the affected-row count of the
 * conditional {@code UPDATE ... WHERE available >= :quantity}, exactly what
 * MySQL returns when the stock is insufficient (0 rows, oversell prevented).
 * The end-to-end proof against a real MySQL container lives in
 * {@code StockOversoldCounterIT}.</p>
 */
@ExtendWith(MockitoExtension.class)
class JpaInventoryRepositoryAdapterTest {

    private static final long PRODUCT_ID = 20L;

    @Mock
    private InventoryStockJpaRepository inventoryStockJpaRepository;

    private SimpleMeterRegistry meterRegistry;
    private JpaInventoryRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        adapter = new JpaInventoryRepositoryAdapter(inventoryStockJpaRepository, meterRegistry);
    }

    @Test
    void conditionalUpdateMatchingZeroRowsIncrementsStockOversoldOnce() {
        given(inventoryStockJpaRepository.reserve(PRODUCT_ID, 21)).willReturn(0);

        assertThat(adapter.reserve(PRODUCT_ID, 21)).isFalse();

        assertThat(stockOversold()).isEqualTo(1.0d);
    }

    @Test
    void successfulReservationDoesNotIncrement() {
        given(inventoryStockJpaRepository.reserve(PRODUCT_ID, 5)).willReturn(1);

        assertThat(adapter.reserve(PRODUCT_ID, 5)).isTrue();

        assertThat(stockOversold()).isZero();
    }

    @Test
    void everyPreventedAttemptCountsExactlyOnce() {
        given(inventoryStockJpaRepository.reserve(PRODUCT_ID, 21)).willReturn(0);
        given(inventoryStockJpaRepository.reserve(PRODUCT_ID, 5)).willReturn(1);
        given(inventoryStockJpaRepository.reserve(PRODUCT_ID, 1)).willReturn(0);

        assertThat(adapter.reserve(PRODUCT_ID, 21)).isFalse();
        assertThat(adapter.reserve(PRODUCT_ID, 5)).isTrue();
        assertThat(adapter.reserve(PRODUCT_ID, 1)).isFalse();

        // Two blocked attempts, one success: the counter follows the guard,
        // not the rejection reason above it.
        assertThat(stockOversold()).isEqualTo(2.0d);
    }

    @Test
    void seckillMetricsEagerSeriesAndTheAdapterShareOneCounter() {
        SeckillMetrics seckillMetrics = new SeckillMetrics(meterRegistry);
        JpaInventoryRepositoryAdapter lateAdapter =
                new JpaInventoryRepositoryAdapter(inventoryStockJpaRepository, meterRegistry);
        given(inventoryStockJpaRepository.reserve(PRODUCT_ID, 3)).willReturn(0);

        lateAdapter.reserve(PRODUCT_ID, 3);

        // Same raw name and tags resolve to the identical Micrometer counter,
        // so the exported stock_oversold_total gets +1 and stays a single
        // series regardless of registration order.
        assertThat(seckillMetrics.stockOversold().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.find(JpaInventoryRepositoryAdapter.STOCK_OVERSOLD_METRIC)
                .counters()).hasSize(1);
    }

    private double stockOversold() {
        Counter counter = meterRegistry
                .find(JpaInventoryRepositoryAdapter.STOCK_OVERSOLD_METRIC)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }
}
