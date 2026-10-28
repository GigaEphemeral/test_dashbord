package com.javalearn.lab.chaos.ledger;

import java.util.Map;

/**
 * One environment-changing request, as the operation contract needs it.
 *
 * @param runId          owning run, or null for operations outside a run
 *                       (fault injection, stop-all)
 * @param targetKind     {@code run}, {@code container} or {@code environment}
 * @param targetName     run id, container name, or the environment scope name
 * @param action         wire action ({@code start}, {@code kill}, {@code stop-all} ...)
 * @param ttlSeconds     fault TTL where the action has one, otherwise null
 * @param idempotencyKey validated key or null
 * @param beforeSummary  credential-free snapshot before the action
 * @param strictLedger   true when an unavailable ledger must fail the request
 *                       (run lifecycle: the run row itself lives in MySQL);
 *                       false for fault actions, which must still run during a
 *                       database outage and are stashed for later replay
 */
public record OperationRequest(
        String runId,
        String targetKind,
        String targetName,
        String action,
        Integer ttlSeconds,
        String idempotencyKey,
        Map<String, Object> beforeSummary,
        boolean strictLedger) {

    /** Convenience for strict operations (run lifecycle). */
    public static OperationRequest strict(String runId, String targetKind, String targetName,
                                          String action, Integer ttlSeconds, String idempotencyKey,
                                          Map<String, Object> beforeSummary) {
        return new OperationRequest(runId, targetKind, targetName, action, ttlSeconds,
                idempotencyKey, beforeSummary, true);
    }
}
