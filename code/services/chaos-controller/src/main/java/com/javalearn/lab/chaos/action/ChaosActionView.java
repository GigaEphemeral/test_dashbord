package com.javalearn.lab.chaos.action;

import java.time.Instant;

/**
 * API view of a chaos action.
 *
 * @param id           action id
 * @param target       container name
 * @param action       action that was applied
 * @param ttlSeconds   TTL for reverting actions, otherwise {@code null}
 * @param expiresAt    scheduled revert time for reverting actions, otherwise {@code null}
 * @param revertAction action that undoes it, otherwise {@code null}
 * @param status       applying/active/reverting/reverted/failed/completed
 * @param lastError    last revert failure reason for failed actions, otherwise {@code null}
 * @param operationId  B1-02 operation contract id ({@code lab_operation}); null only
 *                     for views produced inside {@code ChaosActionService}, which
 *                     does not know about the ledger
 */
public record ChaosActionView(String id,
                              String target,
                              String action,
                              Integer ttlSeconds,
                              Instant expiresAt,
                              String revertAction,
                              String status,
                              String lastError,
                              String operationId) {

    /** Compatibility constructor for callers/tests that predate B1-02. */
    public ChaosActionView(String id, String target, String action, Integer ttlSeconds,
                           Instant expiresAt, String revertAction, String status, String lastError) {
        this(id, target, action, ttlSeconds, expiresAt, revertAction, status, lastError, null);
    }

    /** Copy with the operation id attached by the REST layer. */
    public ChaosActionView withOperationId(String operationId) {
        return new ChaosActionView(id, target, action, ttlSeconds, expiresAt, revertAction, status,
                lastError, operationId);
    }
}
