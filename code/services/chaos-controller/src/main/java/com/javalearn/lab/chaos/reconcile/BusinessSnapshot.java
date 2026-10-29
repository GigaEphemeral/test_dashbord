package com.javalearn.lab.chaos.reconcile;

import java.time.Instant;
import java.util.List;

/**
 * One read-only view of the business schema at a point in time.
 *
 * <p>Business tables have no run id, so run scope is derived from the
 * difference against the baseline captured when the run entered RUNNING. The
 * snapshot is append-only friendly: orders are only ever inserted, inventory is
 * only ever decremented.</p>
 *
 * <p>{@code samples} are bounded by {@code lab.reconcile.max-sample-rows} and
 * are already masked (order numbers / idempotency keys keep a short prefix
 * only), so they can be shown as evidence without leaking whole business
 * identifiers.</p>
 */
public record BusinessSnapshot(
        Instant capturedAt,
        long ordersCount,
        long ordersQuantity,
        long distinctOrderNo,
        long duplicateOrderNo,
        long duplicateIdempotency,
        long inventoryRows,
        long minAvailable,
        long sumAvailable,
        List<ProductSample> products,
        List<String> duplicateOrderNoSamples,
        List<String> duplicateIdempotencySamples) {

    public BusinessSnapshot {
        products = products == null ? List.of() : List.copyOf(products);
        duplicateOrderNoSamples = duplicateOrderNoSamples == null
                ? List.of() : List.copyOf(duplicateOrderNoSamples);
        duplicateIdempotencySamples = duplicateIdempotencySamples == null
                ? List.of() : List.copyOf(duplicateIdempotencySamples);
    }

    /** One inventory row; {@code productId}/{@code available} are not sensitive. */
    public record ProductSample(long productId, long available) {
    }
}
