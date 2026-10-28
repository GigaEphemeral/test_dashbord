package com.javalearn.lab.chaos.ledger;

/**
 * Seams for the run-start path so later steps can plug real work in without
 * changing the operation contract.
 *
 * <p><b>B1-02 is contract-first.</b> Start only advances the persisted state
 * machine DRAFT → VALIDATING → READY → RUNNING and takes the environment lock;
 * there is no real pre-check and no k6 execution yet. The two hooks are
 * deliberately no-ops:</p>
 * <ul>
 *   <li>{@link #precheck(String)} runs between VALIDATING and READY - B1-04
 *       inserts the environment pre-check here and may fail the start with a
 *       blocking finding;</li>
 *   <li>{@link #launch(String)} runs between READY and RUNNING - B2-02 starts
 *       the k6 container here.</li>
 * </ul>
 *
 * <p>Because the hooks run inside the operation worker, a slow hook is exactly
 * the shape that produces PENDING_CONFIRMATION: the HTTP answer comes back
 * after the sync budget while the run keeps progressing.</p>
 */
public interface RunLifecycleHook {

    default void precheck(String runId) {
        // No-op until B1-04.
    }

    default void launch(String runId) {
        // No-op until B2-02.
    }

    static RunLifecycleHook none() {
        return new RunLifecycleHook() {
        };
    }
}
