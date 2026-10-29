package com.javalearn.lab.chaos.reconcile;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read-only reader over the business {@code seckill} schema.
 *
 * <p>Only SELECT statements are issued; the dedicated Hikari pool is also
 * configured read-only with {@code initialization-fail-timeout=-1}. The
 * aggregate query is one round trip; samples are separately bounded by
 * {@code maxSampleRows}. Nothing here writes, locks or repairs business data -
 * reconciliation observes, it never fixes.</p>
 */
public class BusinessSnapshotReader {

    static final String ORDER_AGGREGATE_SQL = """
            SELECT
              (SELECT COUNT(*) FROM order_order) AS orders_count,
              (SELECT COALESCE(SUM(quantity), 0) FROM order_order) AS orders_quantity,
              (SELECT COUNT(DISTINCT order_no) FROM order_order) AS distinct_order_no,
              (SELECT COUNT(*) FROM (SELECT order_no FROM order_order
                 GROUP BY order_no HAVING COUNT(*) > 1) d) AS duplicate_order_no,
              (SELECT COUNT(*) FROM (SELECT idempotency_key FROM order_order
                 GROUP BY idempotency_key HAVING COUNT(*) > 1) d) AS duplicate_idempotency
            """;

    static final String STOCK_AGGREGATE_SQL = """
            SELECT COUNT(*) AS inventory_rows,
                   COALESCE(MIN(available), 0) AS min_available,
                   COALESCE(SUM(available), 0) AS sum_available
            FROM inventory_stock
            """;

    static final String PRODUCT_SAMPLE_SQL = """
            SELECT s.product_id, s.available
            FROM inventory_stock s
            JOIN catalog_product p ON p.id = s.product_id
            ORDER BY s.available ASC, s.product_id ASC
            LIMIT ?
            """;

    static final String DUPLICATE_ORDER_SAMPLE_SQL = """
            SELECT order_no FROM order_order
            GROUP BY order_no HAVING COUNT(*) > 1
            ORDER BY order_no LIMIT ?
            """;

    static final String DUPLICATE_IDEMPOTENCY_SAMPLE_SQL = """
            SELECT idempotency_key FROM order_order
            GROUP BY idempotency_key HAVING COUNT(*) > 1
            ORDER BY idempotency_key LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BusinessSnapshotReader(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public BusinessSnapshot capture(int maxSampleRows) {
        if (maxSampleRows < 1) {
            throw new IllegalArgumentException("maxSampleRows must be >= 1");
        }
        Map<String, Object> orders = jdbc.queryForMap(ORDER_AGGREGATE_SQL);
        Map<String, Object> stock = jdbc.queryForMap(STOCK_AGGREGATE_SQL);
        List<BusinessSnapshot.ProductSample> products = jdbc.query(PRODUCT_SAMPLE_SQL,
                (rs, rowNum) -> new BusinessSnapshot.ProductSample(
                        rs.getLong("product_id"), rs.getLong("available")),
                maxSampleRows);
        List<String> duplicateOrders = jdbc.query(DUPLICATE_ORDER_SAMPLE_SQL,
                (rs, rowNum) -> mask(rs.getString("order_no")), maxSampleRows);
        List<String> duplicateIdempotency = jdbc.query(DUPLICATE_IDEMPOTENCY_SAMPLE_SQL,
                (rs, rowNum) -> mask(rs.getString("idempotency_key")), maxSampleRows);
        return new BusinessSnapshot(
                clock.instant(),
                longValue(orders.get("orders_count")),
                longValue(orders.get("orders_quantity")),
                longValue(orders.get("distinct_order_no")),
                longValue(orders.get("duplicate_order_no")),
                longValue(orders.get("duplicate_idempotency")),
                longValue(stock.get("inventory_rows")),
                longValue(stock.get("min_available")),
                longValue(stock.get("sum_available")),
                products,
                duplicateOrders,
                duplicateIdempotency);
    }

    static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** Keeps a short, non-reversible prefix so evidence never carries a whole key. */
    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "***";
        }
        int keep = Math.min(6, value.length());
        return value.substring(0, keep) + "***";
    }
}
