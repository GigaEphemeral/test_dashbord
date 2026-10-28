package com.javalearn.lab.chaos.ledger;

/**
 * Seams for the run-start path so later steps can plug real work in without
 * changing the operation contract.
 *
 * <p>The environment gate is no longer a hook: B1-04 runs the pre-check
 * <em>before</em> the write-run lock and throws {@code 409/40903} on a blocking
 * finding (see {@code RunPrecheckService}), because a hook called between
 * VALIDATING and READY would already have mutated the run and taken the lock.
 * The remaining seam is {@link #launch(String)}: it runs between READY and
 * RUNNING and is a no-op until B2-02 starts the k6 container.</p>
 *
 * <p>Because the hook runs inside the operation worker, a slow hook is exactly
 * the shape that produces PENDING_CONFIRMATION: the HTTP answer comes back
 * after the sync budget while the run keeps progressing.</p>
 */
public interface RunLifecycleHook {

    default void launch(String runId) {
        // No-op until B2-02.
    }

    static RunLifecycleHook none() {
        return new RunLifecycleHook() {
        };
    }
}
