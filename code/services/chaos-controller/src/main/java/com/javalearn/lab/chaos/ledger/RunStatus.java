package com.javalearn.lab.chaos.ledger;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Run lifecycle (design §10.5).
 *
 * <pre>
 * DRAFT → VALIDATING → READY → RUNNING → STOPPING → VERIFYING → COMPLETED
 *    │          │          │        │          │           └→ FAILED / INVALID
 *    │          └──────────┴────────┴──────────────────────→ FAILED
 *    └─────────────────────────────────────────────────────→ ABORTED
 *
 * A controller restart turns an unfinished RUNNING / STOPPING / VERIFYING run
 * into UNKNOWN (see {@link LedgerRecovery}); UNKNOWN is therefore deliberately
 * absent from every transition target set. An operator closes UNKNOWN as
 * FAILED (confirmed broken) or ABORTED (abandoned) once cleanup is done.
 * </pre>
 */
public enum RunStatus {

    DRAFT,
    VALIDATING,
    READY,
    RUNNING,
    STOPPING,
    VERIFYING,
    COMPLETED,
    FAILED,
    INVALID,
    ABORTED,
    /** Written only by restart recovery; never reachable through a transition. */
    UNKNOWN;

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED;

    static {
        Map<RunStatus, Set<RunStatus>> allowed = new EnumMap<>(RunStatus.class);
        allowed.put(DRAFT, EnumSet.of(VALIDATING, ABORTED));
        allowed.put(VALIDATING, EnumSet.of(READY, FAILED, ABORTED));
        allowed.put(READY, EnumSet.of(RUNNING, FAILED, ABORTED));
        allowed.put(RUNNING, EnumSet.of(STOPPING, FAILED, ABORTED));
        allowed.put(STOPPING, EnumSet.of(VERIFYING, FAILED, ABORTED));
        allowed.put(VERIFYING, EnumSet.of(COMPLETED, FAILED, INVALID, ABORTED));
        // Terminal states have no outgoing edges; UNKNOWN only accepts the two
        // operator closure decisions and is never a transition target itself.
        allowed.put(UNKNOWN, EnumSet.of(FAILED, ABORTED));
        ALLOWED = Collections.unmodifiableMap(allowed);
    }

    /** True when {@code target} is a legal next state for this one. */
    public boolean canTransitionTo(RunStatus target) {
        return target != null && ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /** COMPLETED / FAILED / INVALID / ABORTED cannot transition anywhere. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == INVALID || this == ABORTED;
    }

    /** States a controller restart must not leave as-if still alive. */
    public boolean isUnfinished() {
        return this == RUNNING || this == STOPPING || this == VERIFYING;
    }

    public Set<RunStatus> allowedTargets() {
        return ALLOWED.getOrDefault(this, Set.of());
    }
}
