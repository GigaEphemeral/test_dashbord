package com.javalearn.lab.chaos.ledger;

import java.time.Instant;

/** One row of {@code lab_operation} (the B1-02 contract, persisted here). */
public record OperationRecord(
        String operationId,
        String runId,
        String targetKind,
        String targetName,
        String action,
        OperationStatus status,
        Integer ttlSeconds,
        Instant requestedAt,
        Instant appliedAt,
        Instant restoredAt,
        String lastError) {
}
