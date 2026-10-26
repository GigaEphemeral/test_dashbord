package com.javalearn.lab.seckill.lab.api;

/**
 * One observation of the single-thread lifecycle demo (design §9.2, Task #33).
 *
 * <p>{@code state} / {@code stateCode} are the <strong>real</strong>
 * {@code Thread.getState()} of the dedicated demo thread, mapped onto 0–5
 * (NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED). They are the
 * values Grafana plots from {@code thread_lab_demo_state_code}; this is not the
 * fault-action lifecycle of {@code ThreadActionView}.</p>
 *
 * <p>{@code machineStage} is the state machine's bookkeeping stage. It normally
 * equals {@code state} (while the demo sits in a stage), but can lag it during a
 * natural transition — after the monitor helper releases, the thread is RUNNABLE
 * again while its stage is still BLOCKED until the next advance. The page must
 * animate from {@code state}; {@code machineStage} is there so the next step is
 * predictable (and so {@code freeze} shows where advancement will resume).</p>
 *
 * <p>Display-safe: no configuration secrets and no stack content.</p>
 */
public record LifecycleDemoView(boolean enabled,
                                boolean started,
                                boolean frozen,
                                String state,
                                int stateCode,
                                int step,
                                int maxSteps,
                                String machineStage,
                                String threadName,
                                long threadId,
                                boolean alive) {
}
