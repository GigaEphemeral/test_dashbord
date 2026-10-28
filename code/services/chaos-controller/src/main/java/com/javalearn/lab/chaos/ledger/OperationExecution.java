package com.javalearn.lab.chaos.ledger;

/**
 * What the caller learns about a submitted operation.
 *
 * @param operation current operation row (reloaded after the sync wait)
 * @param replay    true when this request reused an idempotency key inside the
 *                  window and no side effect ran
 * @param pending   true when the sync budget elapsed and the operation is
 *                  PENDING_CONFIRMATION while the worker keeps running
 * @param payload   the work's payload when it finished inside the budget;
 *                  null for pending/replay results
 */
public record OperationExecution(OperationRecord operation, boolean replay, boolean pending, Object payload) {

    /** True when the caller may render the work's own response object. */
    public boolean settledWithPayload() {
        return !replay && !pending && payload != null;
    }
}
