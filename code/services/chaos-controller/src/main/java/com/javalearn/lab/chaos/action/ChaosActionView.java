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
 * @param paused       B2-04a: true while the guide has frozen TTL reverts; the
 *                     countdown is frozen too
 * @param remainingMs  B2-04a: remaining TTL in milliseconds. While paused this is
 *                     the frozen remainder; otherwise {@code max(0, expiresAt - now)}.
 *                     {@code null} for one-shot actions (no TTL) and for
 *                     REVERTED/FAILED actions, whose countdown no longer matters
 */
public record ChaosActionView(String id,
                              String target,
                              String action,
                              Integer ttlSeconds,
                              Instant expiresAt,
                              String revertAction,
                              String status,
                              String lastError,
                              String operationId,
                              boolean paused,
                              Long remainingMs) {

    /**
     * Compatibility constructor for callers/tests that predate B2-04a: no
     * pause/remainder information is attached.
     */
    public ChaosActionView(String id, String target, String action, Integer ttlSeconds,
                           Instant expiresAt, String revertAction, String status, String lastError) {
        this(id, target, action, ttlSeconds, expiresAt, revertAction, status, lastError,
                null, false, null);
    }

    /** Compatibility constructor for callers/tests that predate B2-04a. */
    public ChaosActionView(String id, String target, String action, Integer ttlSeconds,
                           Instant expiresAt, String revertAction, String status, String lastError,
                           String operationId) {
        this(id, target, action, ttlSeconds, expiresAt, revertAction, status, lastError,
                operationId, false, null);
    }

    /** Copy with the operation id attached by the REST layer. */
    public ChaosActionView withOperationId(String operationId) {
        return new ChaosActionView(id, target, action, ttlSeconds, expiresAt, revertAction, status,
                lastError, operationId, paused, remainingMs);
    }

    /** Copy with the B2-04a pause/remaining-TTL snapshot attached by the service. */
    public ChaosActionView withRuntime(boolean paused, Long remainingMs) {
        return new ChaosActionView(id, target, action, ttlSeconds, expiresAt, revertAction, status,
                lastError, operationId, paused, remainingMs);
    }
}
