package com.javalearn.lab.seckill.lab.thread;

/**
 * Lifecycle of a single injected fault, reported to the console so the page can
 * render the state badge and the remaining-TTL countdown (design §9.1, Task #34).
 *
 * <ul>
 *   <li>{@code ACTIVE} — running; an {@code auto} fault is armed with a timer.</li>
 *   <li>{@code PAUSED} — the fault threads keep running but the TTL is frozen:
 *       the remaining time is stored, not counted down.</li>
 *   <li>{@code STOPPED} — terminal; the fault is removed from the active map and
 *       all its threads have been torn down.</li>
 * </ul>
 *
 * <p>The {@link #code()} is exported as {@code thread_lab_lifecycle_state_code} so
 * Grafana can draw the state without string parsing.</p>
 */
public enum LifecycleState {

    ACTIVE(1),
    PAUSED(2),
    STOPPED(0);

    private final int code;

    LifecycleState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
