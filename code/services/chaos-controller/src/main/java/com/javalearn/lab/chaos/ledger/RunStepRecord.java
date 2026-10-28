package com.javalearn.lab.chaos.ledger;

import java.time.Instant;

/** One row of {@code lab_run_step}: a state-machine timeline entry. */
public record RunStepRecord(
        String runId,
        String stepKey,
        RunStatus fromStatus,
        RunStatus toStatus,
        Instant occurredAt,
        Long dwellMs,
        String detailJson) {
}
