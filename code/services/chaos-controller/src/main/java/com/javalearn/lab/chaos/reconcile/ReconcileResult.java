package com.javalearn.lab.chaos.reconcile;

import java.util.List;

/**
 * Result of {@code POST /lab/runs/{runId}/reconcile} and
 * {@code GET /lab/runs/{runId}/reconciliation}.
 *
 * <p>{@code verdict} is PASS only when every item is PASS. Any unwired or
 * unreachable probe is DEGRADED (never a synthetic 0); any failed business
 * invariant is FAIL. Performance thresholds are deliberately not part of this
 * verdict.</p>
 */
public record ReconcileResult(String runId, String checkedAt, String verdict,
                              List<ReconcileItem> items) {

    public ReconcileResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
