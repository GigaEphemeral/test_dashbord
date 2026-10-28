package com.javalearn.lab.chaos.ledger;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Operation contract states (design §10.5). A request timeout is not a
 * failure: the operation becomes PENDING_CONFIRMATION until the controller can
 * prove what happened. After a restart, REQUESTED / PENDING_CONFIRMATION
 * operations become UNKNOWN (never assumed reverted).
 *
 * <pre>
 * REQUESTED ─→ PENDING_CONFIRMATION ─→ APPLIED / RESTORED / FAILED
 *     ├──────→ APPLIED / RESTORED / FAILED          (finished inside the budget)
 *     └──────→ UNKNOWN                              (restart recovery only)
 * PENDING_CONFIRMATION ─────────────→ UNKNOWN       (restart recovery only)
 *
 * APPLIED / RESTORED / FAILED / UNKNOWN are terminal here: a fault that is
 * reverted by its TTL is a new contract revision in B2-04, not an in-place
 * rewrite of the original APPLIED row.
 * </pre>
 */
public enum OperationStatus {

    REQUESTED,
    APPLIED,
    RESTORED,
    PENDING_CONFIRMATION,
    FAILED,
    UNKNOWN;

    private static final Map<OperationStatus, Set<OperationStatus>> ALLOWED;

    static {
        Map<OperationStatus, Set<OperationStatus>> allowed = new EnumMap<>(OperationStatus.class);
        allowed.put(REQUESTED, EnumSet.of(PENDING_CONFIRMATION, APPLIED, RESTORED, FAILED, UNKNOWN));
        allowed.put(PENDING_CONFIRMATION, EnumSet.of(APPLIED, RESTORED, FAILED, UNKNOWN));
        ALLOWED = Collections.unmodifiableMap(allowed);
    }

    /** True when {@code target} is a legal next state for this one. */
    public boolean canTransitionTo(OperationStatus target) {
        return target != null && ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /** Operations that a restart cannot prove either way. */
    public boolean isUnconfirmed() {
        return this == REQUESTED || this == PENDING_CONFIRMATION;
    }

    /** Terminal states are never rewritten (first verdict wins). */
    public boolean isTerminal() {
        return this == APPLIED || this == RESTORED || this == FAILED || this == UNKNOWN;
    }
}
