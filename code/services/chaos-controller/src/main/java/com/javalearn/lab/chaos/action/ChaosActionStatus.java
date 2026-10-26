package com.javalearn.lab.chaos.action;

import java.util.Locale;

/** Lifecycle of a tracked (reverting) chaos action. */
public enum ChaosActionStatus {

    /** Applied; a revert is scheduled. */
    ACTIVE,
    /** Revert currently running. */
    REVERTING,
    /** Reverted successfully and removed from the active set. */
    REVERTED,
    /** Revert failed; kept visible and retried by the watchdog. */
    FAILED,
    /** One-shot action finished (start/restart/resume). */
    COMPLETED;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
