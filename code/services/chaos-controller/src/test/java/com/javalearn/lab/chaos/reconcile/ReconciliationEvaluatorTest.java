package com.javalearn.lab.chaos.reconcile;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PASS/FAIL/DEGRADED matrix for the seven B3-04 checks. */
class ReconciliationEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final String AT = NOW.toString();

    private final ReconciliationEvaluator evaluator = new ReconciliationEvaluator();

    @Test
    void passRequiresEveryItemToPass() {
        ReconcileResult result = evaluator.evaluate("run-1", AT, baseline(),
                snapshot(15, 15, 15, 89, 995, 0, 0),
                mq(true, 0, 120), oversold(true, 0d));

        assertThat(result.verdict()).isEqualTo("PASS");
        assertThat(result.items()).hasSize(7);
        assertThat(result.items()).allMatch(item -> "PASS".equals(item.verdict()));
        assertThat(item(result, "orders.delta").actual())
                .isEqualTo("ordersDelta=5, distinctOrderNoDelta=5");
        assertThat(item(result, "stock.delta").actual())
                .isEqualTo("quantityDelta=5, stockDecrement=5");
        assertThat(item(result, "stock.delta").sample()).containsExactly("p1=89", "p2=89");
    }

    @Test
    void ordersDeltaMismatchFails() {
        ReconcileResult result = evaluator.evaluate("run-1", AT, baseline(),
                snapshot(15, 15, 14, 89, 995, 0, 0),
                mq(true, 0, 120), oversold(true, 0d));

        assertThat(item(result, "orders.delta").verdict()).isEqualTo("FAIL");
        assertThat(result.verdict()).isEqualTo("FAIL");
    }

    @Test
    void stockDeltaMismatchFails() {
        ReconcileResult result = evaluator.evaluate("run-1", AT, baseline(),
                snapshot(15, 15, 15, 89, 980, 0, 0),
                mq(true, 0, 120), oversold(true, 0d));

        assertThat(item(result, "stock.delta").verdict()).isEqualTo("FAIL");
        assertThat(result.verdict()).isEqualTo("FAIL");
    }

    @Test
    void negativeStockFails() {
        ReconcileResult result = evaluator.evaluate("run-1", AT, baseline(),
                snapshot(15, 15, 15, -1, 995, 0, 0),
                mq(true, 0, 120), oversold(true, 0d));

        assertThat(item(result, "stock.nonnegative").verdict()).isEqualTo("FAIL");
        assertThat(result.verdict()).isEqualTo("FAIL");
    }

    @Test
    void duplicateOrdersAndIdempotencyKeysFail() {
        ReconcileResult result = evaluator.evaluate("run-1", AT, baseline(),
                snapshot(15, 15, 15, 89, 985, 1, 2),
                mq(true, 0, 120), oversold(true, 0d));

        assertThat(item(result, "orders.duplicates").verdict()).isEqualTo("FAIL");
        assertThat(item(result, "idempotency.duplicates").verdict()).isEqualTo("FAIL");
        assertThat(item(result, "orders.duplicates").sample()).containsExactly("ORD001***");
        assertThat(result.verdict()).isEqualTo("FAIL");
    }

    @Test
    void missingOrdersBaselineDegradesBothDeltaChecks() {
        ReconciliationBaseline partial = new ReconciliationBaseline(null,
                new ReconciliationBaseline.Stock(10, 100, 1000),
                new ReconciliationBaseline.Mq(true, 0, 0, "captured"));

        ReconcileResult result = evaluator.evaluate("run-1", AT, partial,
                snapshot(15, 15, 15, 89, 995, 0, 0),
                mq(true, 0, 120), oversold(true, 0d));

        assertThat(item(result, "orders.delta").verdict()).isEqualTo("DEGRADED");
        assertThat(item(result, "stock.delta").verdict()).isEqualTo("DEGRADED");
        assertThat(item(result, "orders.duplicates").verdict()).isEqualTo("PASS");
        assertThat(result.verdict()).isEqualTo("DEGRADED");
    }

    @Test
    void missingMqBaselineDegradesEvenWhenQueueIsDrained() {
        ReconciliationBaseline partial = new ReconciliationBaseline(
                baseline().orders(), baseline().stock(), null);

        ReconcileResult result = evaluator.evaluate("run-1", AT, partial,
                snapshot(15, 15, 15, 89, 995, 0, 0),
                mq(true, 0, 120), oversold(true, 0d));

        assertThat(item(result, "mq.drained").verdict()).isEqualTo("DEGRADED");
        assertThat(result.verdict()).isEqualTo("DEGRADED");
    }

    @Test
    void unwiredOversoldCounterIsDegradedNeverZero() {
        ReconcileResult result = evaluator.evaluate("run-1", AT, baseline(),
                snapshot(15, 15, 15, 89, 995, 0, 0),
                mq(true, 0, 120), oversold(false, 0d));

        ReconcileItem item = item(result, "oversold.counter");
        assertThat(item.verdict()).isEqualTo("DEGRADED");
        assertThat(item.actual()).isEqualTo("not evaluated");
        assertThat(result.verdict()).isEqualTo("DEGRADED");
    }

    @Test
    void positiveOversoldCounterFails() {
        ReconcileResult result = evaluator.evaluate("run-1", AT, baseline(),
                snapshot(15, 15, 15, 89, 995, 0, 0),
                mq(true, 0, 120), oversold(true, 2d));

        assertThat(item(result, "oversold.counter").verdict()).isEqualTo("FAIL");
        assertThat(result.verdict()).isEqualTo("FAIL");
    }

    @Test
    void unreachableRabbitIsDegraded() {
        ReconcileResult result = evaluator.evaluate("run-1", AT, baseline(),
                snapshot(15, 15, 15, 89, 995, 0, 0),
                mq(false, 0, 120), oversold(true, 0d));

        assertThat(item(result, "mq.drained").verdict()).isEqualTo("DEGRADED");
        assertThat(result.verdict()).isEqualTo("DEGRADED");
    }

    @Test
    void reachableButUndrainedQueueFails() {
        ReconcileResult result = evaluator.evaluate("run-1", AT, baseline(),
                snapshot(15, 15, 15, 89, 995, 0, 0),
                mq(true, 3, 120), oversold(true, 0d));

        assertThat(item(result, "mq.drained").verdict()).isEqualTo("FAIL");
        assertThat(result.verdict()).isEqualTo("FAIL");
    }

    @Test
    void unavailableBusinessSnapshotDegradesEveryItem() {
        ReconcileResult result = evaluator.unavailable("run-1", AT, "mysql down");

        assertThat(result.verdict()).isEqualTo("DEGRADED");
        assertThat(result.items()).hasSize(7).allMatch(item ->
                "DEGRADED".equals(item.verdict()) && "mysql down".equals(item.evidence()));
    }

    private static ReconcileItem item(ReconcileResult result, String key) {
        return result.items().stream().filter(item -> key.equals(item.key())).findFirst()
                .orElseThrow();
    }

    private static ReconciliationEvaluator.MqProbe mq(boolean reachable, long ready, long elapsed) {
        return new ReconciliationEvaluator.MqProbe(reachable, ready, 0, elapsed,
                "queue messages_ready=" + ready + ", consumers=3");
    }

    private static ReconciliationEvaluator.OversoldProbe oversold(boolean wired, double value) {
        return new ReconciliationEvaluator.OversoldProbe(wired, value,
                "prometheus sum=" + value);
    }

    private static ReconciliationBaseline baseline() {
        return new ReconciliationBaseline(
                new ReconciliationBaseline.Orders(10, 10, 10, 0, 0),
                new ReconciliationBaseline.Stock(10, 100, 1000),
                new ReconciliationBaseline.Mq(true, 0, 0, "captured"));
    }

    private static BusinessSnapshot snapshot(long orders, long quantity, long distinct,
                                             long minAvailable, long sumAvailable,
                                             long duplicateOrders, long duplicateIdempotency) {
        return new BusinessSnapshot(NOW, orders, quantity, distinct, duplicateOrders,
                duplicateIdempotency, 10, minAvailable, sumAvailable,
                List.of(new BusinessSnapshot.ProductSample(1L, minAvailable),
                        new BusinessSnapshot.ProductSample(2L, 89)),
                duplicateOrders > 0 ? List.of("ORD001***") : List.of(),
                duplicateIdempotency > 0 ? List.of("idem-1***") : List.of());
    }
}
