package com.javalearn.lab.chaos.reconcile;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Snapshot SQL mapping without a database: aggregate values, sample bounds and
 * key masking. SQL table/column names were verified live against
 * {@code jl-mysql} (seckill.order_order / inventory_stock / catalog_product).
 */
class ReconciliationSnapshotReaderTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void mapsAggregatesAndBoundedSamples() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(contains("AS orders_count"))).thenReturn(Map.of(
                "orders_count", 22L,
                "orders_quantity", 22L,
                "distinct_order_no", 22L,
                "duplicate_order_no", 0L,
                "duplicate_idempotency", 0L));
        when(jdbc.queryForMap(contains("AS inventory_rows"))).thenReturn(Map.of(
                "inventory_rows", 1020L,
                "min_available", 89L,
                "sum_available", 101978L));
        when(jdbc.query(contains("FROM inventory_stock s"), any(RowMapper.class), eq(3)))
                .thenReturn(List.of(
                        new BusinessSnapshot.ProductSample(1L, 89L),
                        new BusinessSnapshot.ProductSample(20L, 89L),
                        new BusinessSnapshot.ProductSample(2L, 100L)));
        when(jdbc.query(contains("SELECT order_no FROM order_order"), any(RowMapper.class), eq(3)))
                .thenReturn(List.of("ORD001***"));
        when(jdbc.query(contains("SELECT idempotency_key FROM order_order"),
                any(RowMapper.class), eq(3))).thenReturn(List.of("idem-1***"));

        BusinessSnapshotReader reader = new BusinessSnapshotReader(jdbc,
                Clock.fixed(NOW, ZoneOffset.UTC));
        BusinessSnapshot snapshot = reader.capture(3);

        assertThat(snapshot.capturedAt()).isEqualTo(NOW);
        assertThat(snapshot.ordersCount()).isEqualTo(22);
        assertThat(snapshot.ordersQuantity()).isEqualTo(22);
        assertThat(snapshot.distinctOrderNo()).isEqualTo(22);
        assertThat(snapshot.duplicateOrderNo()).isZero();
        assertThat(snapshot.duplicateIdempotency()).isZero();
        assertThat(snapshot.inventoryRows()).isEqualTo(1020);
        assertThat(snapshot.minAvailable()).isEqualTo(89);
        assertThat(snapshot.sumAvailable()).isEqualTo(101978);
        assertThat(snapshot.products()).containsExactly(
                new BusinessSnapshot.ProductSample(1L, 89L),
                new BusinessSnapshot.ProductSample(20L, 89L),
                new BusinessSnapshot.ProductSample(2L, 100L));
        assertThat(snapshot.duplicateOrderNoSamples()).containsExactly("ORD001***");
        assertThat(snapshot.duplicateIdempotencySamples()).containsExactly("idem-1***");
        verify(jdbc).query(contains("FROM inventory_stock s"), any(RowMapper.class), eq(3));
    }

    @Test
    void masksDuplicateKeysAndHandlesNullNumbers() {
        assertThat(BusinessSnapshotReader.mask("ORD-123456789")).isEqualTo("ORD-12***");
        assertThat(BusinessSnapshotReader.mask("abc")).isEqualTo("abc***");
        assertThat(BusinessSnapshotReader.mask(null)).isEqualTo("***");
        assertThat(BusinessSnapshotReader.longValue(null)).isZero();
        assertThat(BusinessSnapshotReader.longValue(7)).isEqualTo(7L);
    }
}
