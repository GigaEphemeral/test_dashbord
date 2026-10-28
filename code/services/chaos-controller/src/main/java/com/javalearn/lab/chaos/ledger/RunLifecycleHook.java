package com.javalearn.lab.chaos.ledger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seams for the run lifecycle so the k6 execution layer can plug real work in
 * without changing the operation contract.
 *
 * <p>The environment gate is no longer a hook: B1-04 runs the pre-check
 * <em>before</em> the write-run lock and throws {@code 409/40903} on a blocking
 * finding (see {@code RunPrecheckService}), because a hook called between
 * VALIDATING and READY would already have mutated the run and taken the lock.
 * The remaining seams are {@link #launch(String)} (READY → RUNNING) and
 * {@link #stop(String)} (after RUNNING → STOPPING), implemented by
 * {@code BenchRunLifecycleHook} since B2-02b-2a.</p>
 *
 * <p>Both methods return a serializable detail map (for example
 * {@code k6Container}/{@code k6Scenario}/{@code k6ReportPath}) that the
 * lifecycle service merges into the operation's after-summary. The default
 * implementation is still a no-op returning {@link LaunchResult#none()}, so
 * contract-first tests and {@link #none()} keep working.</p>
 *
 * <p>Because the launch hook runs inside the operation worker, a slow hook is
 * exactly the shape that produces PENDING_CONFIRMATION: the HTTP answer comes
 * back after the sync budget while the run keeps progressing.</p>
 */
public interface RunLifecycleHook {

    /**
     * Starts whatever the run needs (k6 container) between READY and RUNNING.
     *
     * @return a small, credential-free summary for the start operation
     */
    default LaunchResult launch(String runId) {
        return LaunchResult.none();
    }

    /**
     * Best-effort stop invoked after RUNNING → STOPPING (operator stop) and
     * while closing a failed start. Implementations must be idempotent: an
     * absent container is a normal answer.
     *
     * @return a small, credential-free summary for the stop operation
     */
    default LaunchResult stop(String runId) {
        return LaunchResult.none();
    }

    static RunLifecycleHook none() {
        return new RunLifecycleHook() {
        };
    }

    /**
     * Serializable summary of one hook call. Values are merged into the
     * operation's after-summary; keys use the {@code k6*} prefix so they cannot
     * collide with {@code runStatus}/{@code lock*}.
     */
    record LaunchResult(Map<String, Object> details) {

        public LaunchResult {
            Map<String, Object> copy = new LinkedHashMap<>();
            if (details != null) {
                copy.putAll(details);
            }
            details = Collections.unmodifiableMap(copy);
        }

        public static LaunchResult none() {
            return new LaunchResult(Map.of());
        }
    }
}
