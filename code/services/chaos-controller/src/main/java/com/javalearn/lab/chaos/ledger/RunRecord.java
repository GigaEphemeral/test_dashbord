package com.javalearn.lab.chaos.ledger;

import java.time.Instant;

/** One row of {@code lab_run}. Instants are stored as UTC {@code DATETIME(3)}. */
public record RunRecord(
        String runId,
        String scenario,
        RunStatus status,
        String sourceCommit,
        String operator,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant endedAt) {
}
