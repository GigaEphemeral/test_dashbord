package com.javalearn.lab.chaos.reconcile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure verdict logic for B3-04. Keeping it free of JDBC/HTTP makes the
 * PASS/FAIL/DEGRADED matrix directly testable.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>business invariant violated -> FAIL;</li>
 *   <li>baseline missing, Prometheus series absent, RabbitMQ unreachable or
 *       reconciliation disabled -> DEGRADED (never a synthetic 0, never PASS);</li>
 *   <li>PASS only when every item is PASS.</li>
 * </ul>
 */
public final class ReconciliationEvaluator {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String DEGRADED = "DEGRADED";

    public static final String KEY_ORDERS_DELTA = "orders.delta";
    public static final String KEY_STOCK_DELTA = "stock.delta";
    public static final String KEY_STOCK_NONNEGATIVE = "stock.nonnegative";
    public static final String KEY_ORDERS_DUPLICATES = "orders.duplicates";
    public static final String KEY_IDEMPOTENCY_DUPLICATES = "idempotency.duplicates";
    public static final String KEY_OVERSOLD_COUNTER = "oversold.counter";
    public static final String KEY_MQ_DRAINED = "mq.drained";

    public static final List<String> KEYS = List.of(
            KEY_ORDERS_DELTA, KEY_STOCK_DELTA, KEY_STOCK_NONNEGATIVE, KEY_ORDERS_DUPLICATES,
            KEY_IDEMPOTENCY_DUPLICATES, KEY_OVERSOLD_COUNTER, KEY_MQ_DRAINED);

    public ReconciliationEvaluator() {
    }

    /** Result of the MQ drain probe; {@code reachable=false} means DEGRADED. */
    public record MqProbe(boolean reachable, long messagesReady, long messagesUnacknowledged,
                          long elapsedMs, String detail) {
    }

    /** Result of the Prometheus oversold probe; {@code wired=false} means DEGRADED. */
    public record OversoldProbe(boolean wired, double value, String detail) {
    }

    public ReconcileResult evaluate(String runId, String checkedAt,
                                    ReconciliationBaseline baseline,
                                    BusinessSnapshot current,
                                    MqProbe mq,
                                    OversoldProbe oversold) {
        List<ReconcileItem> items = new ArrayList<>(KEYS.size());
        items.add(ordersDelta(baseline == null ? null : baseline.orders(), current));
        items.add(stockDelta(baseline, current));
        items.add(stockNonNegative(current));
        items.add(ordersDuplicates(current));
        items.add(idempotencyDuplicates(current));
        items.add(oversold(oversold));
        items.add(mqDrained(baseline == null ? null : baseline.mq(), mq));
        return new ReconcileResult(runId, checkedAt, overall(items), items);
    }

    /**
     * Business data could not be read at all. Every item is DEGRADED with the
     * same reason, so the operator sees why no comparison happened.
     */
    public ReconcileResult unavailable(String runId, String checkedAt, String reason) {
        List<ReconcileItem> items = new ArrayList<>(KEYS.size());
        for (String key : KEYS) {
            items.add(new ReconcileItem(key, "comparable business snapshot",
                    "not available", DEGRADED, reason, List.of()));
        }
        return new ReconcileResult(runId, checkedAt, DEGRADED, items);
    }

    private ReconcileItem ordersDelta(ReconciliationBaseline.Orders baseline,
                                      BusinessSnapshot current) {
        if (baseline == null) {
            return degraded(KEY_ORDERS_DELTA, "orders delta >= 0 and equals distinct new order_no",
                    "baseline.orders missing - baseline capture failed",
                    current == null ? List.of() : orderSamples(current));
        }
        long ordersDelta = current.ordersCount() - baseline.ordersCount();
        long distinctDelta = current.distinctOrderNo() - baseline.distinctOrderNo();
        boolean pass = ordersDelta >= 0 && ordersDelta == distinctDelta;
        return new ReconcileItem(KEY_ORDERS_DELTA,
                "orders delta >= 0 and equals distinct new order_no",
                "ordersDelta=" + ordersDelta + ", distinctOrderNoDelta=" + distinctDelta,
                pass ? PASS : FAIL,
                "baseline.count=" + baseline.ordersCount()
                        + ", current.count=" + current.ordersCount()
                        + ", baseline.distinctOrderNo=" + baseline.distinctOrderNo()
                        + ", current.distinctOrderNo=" + current.distinctOrderNo(),
                List.of());
    }

    private ReconcileItem stockDelta(ReconciliationBaseline baseline, BusinessSnapshot current) {
        List<String> samples = stockSamples(current);
        if (baseline == null || baseline.stock() == null || baseline.orders() == null) {
            return degraded(KEY_STOCK_DELTA,
                    "sum(new order.quantity) == stock decrement (tolerance 0)",
                    "baseline.stock or baseline.orders missing - baseline capture failed", samples);
        }
        long quantityDelta = current.ordersQuantity() - baseline.orders().ordersQuantity();
        long stockDecrement = baseline.stock().sumAvailable() - current.sumAvailable();
        boolean pass = quantityDelta >= 0 && stockDecrement >= 0 && quantityDelta == stockDecrement;
        return new ReconcileItem(KEY_STOCK_DELTA,
                "sum(new order.quantity) == stock decrement (tolerance 0)",
                "quantityDelta=" + quantityDelta + ", stockDecrement=" + stockDecrement,
                pass ? PASS : FAIL,
                "baseline.quantity=" + baseline.orders().ordersQuantity()
                        + ", current.quantity=" + current.ordersQuantity()
                        + ", baseline.sumAvailable=" + baseline.stock().sumAvailable()
                        + ", current.sumAvailable=" + current.sumAvailable()
                        + ", tolerance=0",
                samples);
    }

    private ReconcileItem stockNonNegative(BusinessSnapshot current) {
        boolean pass = current.minAvailable() >= 0;
        return new ReconcileItem(KEY_STOCK_NONNEGATIVE, "min(available) >= 0",
                "minAvailable=" + current.minAvailable(), pass ? PASS : FAIL,
                "inventoryRows=" + current.inventoryRows() + ", sumAvailable="
                        + current.sumAvailable(), stockSamples(current));
    }

    private ReconcileItem ordersDuplicates(BusinessSnapshot current) {
        boolean pass = current.duplicateOrderNo() == 0;
        return new ReconcileItem(KEY_ORDERS_DUPLICATES, "duplicate order_no = 0",
                "duplicateOrderNo=" + current.duplicateOrderNo(), pass ? PASS : FAIL,
                "COUNT(*) grouped by order_no HAVING COUNT(*) > 1",
                current.duplicateOrderNoSamples());
    }

    private ReconcileItem idempotencyDuplicates(BusinessSnapshot current) {
        boolean pass = current.duplicateIdempotency() == 0;
        return new ReconcileItem(KEY_IDEMPOTENCY_DUPLICATES, "duplicate idempotency_key = 0",
                "duplicateIdempotency=" + current.duplicateIdempotency(), pass ? PASS : FAIL,
                "COUNT(*) grouped by idempotency_key HAVING COUNT(*) > 1",
                current.duplicateIdempotencySamples());
    }

    private ReconcileItem oversold(OversoldProbe probe) {
        if (probe == null || !probe.wired()) {
            return degraded(KEY_OVERSOLD_COUNTER, "stock_oversold_total == 0",
                    probe == null ? "oversold probe not wired" : probe.detail(), List.of());
        }
        boolean pass = probe.value() == 0d;
        return new ReconcileItem(KEY_OVERSOLD_COUNTER, "stock_oversold_total == 0",
                "stock_oversold_total=" + number(probe.value()), pass ? PASS : FAIL,
                probe.detail(), List.of());
    }

    private ReconcileItem mqDrained(ReconciliationBaseline.Mq baseline, MqProbe probe) {
        if (probe == null || !probe.reachable()) {
            return degraded(KEY_MQ_DRAINED, "messages_ready == 0 within the drain budget",
                    probe == null ? "RabbitMQ probe not available" : probe.detail(), List.of());
        }
        if (baseline == null || !baseline.reachable()) {
            return degraded(KEY_MQ_DRAINED, "messages_ready == 0 within the drain budget",
                    "baseline.mq missing or unreachable at run start - capture failed; current="
                            + queueEvidence(probe), List.of());
        }
        boolean pass = probe.messagesReady() == 0;
        return new ReconcileItem(KEY_MQ_DRAINED, "messages_ready == 0 within the drain budget",
                "messages_ready=" + probe.messagesReady() + " after " + probe.elapsedMs() + "ms",
                pass ? PASS : FAIL,
                queueEvidence(probe) + ", baseline.messages_ready=" + baseline.messagesReady()
                        + ", baseline.messages_unacknowledged="
                        + baseline.messagesUnacknowledged(), List.of());
    }

    private static String queueEvidence(MqProbe probe) {
        return probe.detail() + ", messages_unacknowledged=" + probe.messagesUnacknowledged();
    }

    private static ReconcileItem degraded(String key, String expected, String evidence,
                                          List<String> sample) {
        return new ReconcileItem(key, expected, "not evaluated", DEGRADED, evidence, sample);
    }

    private static List<String> orderSamples(BusinessSnapshot current) {
        return current.duplicateOrderNoSamples();
    }

    private static List<String> stockSamples(BusinessSnapshot current) {
        return current == null ? List.of() : current.products().stream()
                .map(sample -> "p" + sample.productId() + "=" + sample.available())
                .toList();
    }

    public static String overall(List<ReconcileItem> items) {
        boolean degraded = false;
        for (ReconcileItem item : items) {
            if (FAIL.equals(item.verdict())) {
                return FAIL;
            }
            if (DEGRADED.equals(item.verdict())) {
                degraded = true;
            }
        }
        return degraded ? DEGRADED : PASS;
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
