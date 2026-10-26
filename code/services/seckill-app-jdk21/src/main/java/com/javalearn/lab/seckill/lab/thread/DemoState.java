package com.javalearn.lab.seckill.lab.thread;

/**
 * The six JVM thread states of the single-thread lifecycle demo (design §9.2,
 * Task #33), in the order the demo walks them:
 * {@code NEW → RUNNABLE → BLOCKED → WAITING → TIMED_WAITING → TERMINATED}.
 *
 * <p>This is deliberately a <em>different</em> enum from {@link LifecycleState}:
 * that one is the fault-action lifecycle ({@code ACTIVE/PAUSED/STOPPED}) exported
 * as {@code thread_lab_lifecycle_state_code} and is part of the console key
 * contract. The demo's code is exported as {@code thread_lab_demo_state_code},
 * so the two timelines cannot collide.</p>
 *
 * <p>The code is the Grafana state-timeline value (0–5). Every observation is
 * derived from a real {@link Thread#getState()} of the demo thread through
 * {@link #from(Thread.State)} — the enum is a wire format, not bookkeeping.</p>
 */
public enum DemoState {

    NEW(0),
    RUNNABLE(1),
    BLOCKED(2),
    WAITING(3),
    TIMED_WAITING(4),
    TERMINATED(5);

    private final int code;

    DemoState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** Maps a real JVM thread state onto the exported code space. */
    public static DemoState from(Thread.State state) {
        return switch (state) {
            case NEW -> NEW;
            case RUNNABLE -> RUNNABLE;
            case BLOCKED -> BLOCKED;
            case WAITING -> WAITING;
            case TIMED_WAITING -> TIMED_WAITING;
            case TERMINATED -> TERMINATED;
        };
    }
}
