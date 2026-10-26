package com.javalearn.lab.chaos.config;

import java.util.Locale;

/**
 * What happens to injected faults when the interactive guide finishes.
 *
 * <p>The lab defaults to {@link #MANUAL}: the operator keeps the fault in place
 * to read logs, traces and dashboards and reverts it with TTL, {@code stop-all}
 * or {@code reset}. {@link #AUTO} reverts every tracked fault as soon as the
 * guide reaches COMPLETED or FAILED, which is what batch runs want.</p>
 */
public enum ResetMode {

    /** Keep faults until the operator reverts them. */
    MANUAL,

    /** Run stop-all automatically when the guide reaches a terminal state. */
    AUTO;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
