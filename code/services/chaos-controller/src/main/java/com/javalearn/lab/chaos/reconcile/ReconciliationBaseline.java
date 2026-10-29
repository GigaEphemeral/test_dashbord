package com.javalearn.lab.chaos.reconcile;

/**
 * Baseline captured when the run enters RUNNING. Any missing part makes the
 * affected reconciliation item DEGRADED - a failed capture never fails the run,
 * but it can never produce a PASS either.
 */
public record ReconciliationBaseline(Orders orders, Stock stock, Mq mq) {

    public record Orders(long ordersCount, long ordersQuantity, long distinctOrderNo,
                         long duplicateOrderNo, long duplicateIdempotency) {
    }

    public record Stock(long inventoryRows, long minAvailable, long sumAvailable) {
    }

    public record Mq(boolean reachable, long messagesReady, long messagesUnacknowledged,
                     String detail) {
    }

    public boolean complete() {
        return orders != null && stock != null && mq != null && mq.reachable();
    }
}
