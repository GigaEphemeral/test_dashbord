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
 * @param status       active/reverting/reverted/failed/completed
 */
public record ChaosActionView(String id,
                              String target,
                              String action,
                              Integer ttlSeconds,
                              Instant expiresAt,
                              String revertAction,
                              String status) {
}
