package com.javalearn.lab.chaos.ledger;

import java.util.Map;

/**
 * Result of one operation's work.
 *
 * @param afterSummary credential-free snapshot after the action (may be empty)
 * @param restored     true when the action restores state (fault revert,
 *                     stop-all, container start/resume) instead of applying a
 *                     change: the operation settles as RESTORED, otherwise
 *                     APPLIED
 * @param payload      optional object returned to the caller (the chaos action
 *                     view); never persisted
 */
public record OperationOutcome(Map<String, Object> afterSummary, boolean restored, Object payload) {

    public static OperationOutcome applied(Map<String, Object> afterSummary, Object payload) {
        return new OperationOutcome(afterSummary, false, payload);
    }

    public static OperationOutcome restored(Map<String, Object> afterSummary, Object payload) {
        return new OperationOutcome(afterSummary, true, payload);
    }
}
