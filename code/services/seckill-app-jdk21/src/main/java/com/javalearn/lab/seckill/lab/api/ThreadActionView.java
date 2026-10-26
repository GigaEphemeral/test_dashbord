package com.javalearn.lab.seckill.lab.api;

import java.util.List;

/**
 * One injected fault rendered for the console (design §9.1, D6 / Task #34).
 *
 * <p>Display-safe by construction: it carries only the mode, the hold choice, the
 * lifecycle state, the started-at epoch millis, the remaining TTL (millis;
 * {@code -1} for a manual hold that never reverts) and the participant thread ids.
 * No configuration secrets and no stack content. Built by
 * {@code ThreadFaultService}.</p>
 */
public record ThreadActionView(String mode,
                               String hold,
                               String state,
                               long startedAtEpochMs,
                               long remainingTtlMillis,
                               List<Long> threadIds) {
}
