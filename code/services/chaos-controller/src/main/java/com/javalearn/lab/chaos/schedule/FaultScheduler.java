package com.javalearn.lab.chaos.schedule;

import java.time.Instant;

/**
 * B3-00b seam used by the run lifecycle and by environment stop-all.
 *
 * <p>The no-op instance keeps direct construction (tests, ledger-only tools)
 * working without a scheduler; production wiring always uses
 * {@link FaultScheduleService}.</p>
 */
public interface FaultScheduler {

    /**
     * Arms every persisted entry of {@code runId} at {@code runStart + atSeconds}.
     * Idempotent per run: a second call returns the already-armed count without
     * scheduling anything again.
     *
     * @return number of entries armed (0 when the run has no faultSchedule)
     * @throws RuntimeException when the persisted group cannot be read or a
     *         task cannot be scheduled; the caller fails the start
     */
    int arm(String runId, Instant runStart);

    /** Cancels pending entries of a run. Must never throw. */
    void cancel(String runId);

    FaultScheduler NONE = new FaultScheduler() {
        @Override
        public int arm(String runId, Instant runStart) {
            return 0;
        }

        @Override
        public void cancel(String runId) {
            // no schedule was armed
        }
    };
}
