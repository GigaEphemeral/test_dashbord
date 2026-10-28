package com.javalearn.lab.chaos.ledger;

import java.time.Instant;

/**
 * One row of {@code lab_operation} (the B1-02 operation contract).
 *
 * <p>The last three components are the B1-02 audit fields: {@code operator} is
 * the configured audit identity, {@code beforeSummaryJson} /
 * {@code afterSummaryJson} are the credential-free snapshots served by
 * {@code GET /lab/operations/{id}}. The 11-argument constructor keeps rows that
 * predate B1-02 (and tests that do not care about summaries) source-compatible.
 * The idempotency key is deliberately not part of this record: it is only
 * needed to insert and to look up a row, never to render it.</p>
 */
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
        String lastError,
        String operator,
        String beforeSummaryJson,
        String afterSummaryJson) {

    public OperationRecord(String operationId, String runId, String targetKind, String targetName,
                           String action, OperationStatus status, Integer ttlSeconds,
                           Instant requestedAt, Instant appliedAt, Instant restoredAt,
                           String lastError) {
        this(operationId, runId, targetKind, targetName, action, status, ttlSeconds, requestedAt,
                appliedAt, restoredAt, lastError, null, null, null);
    }
}
