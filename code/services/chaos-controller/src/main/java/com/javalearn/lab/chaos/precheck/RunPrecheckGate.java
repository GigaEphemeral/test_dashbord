package com.javalearn.lab.chaos.precheck;

import com.javalearn.lab.chaos.error.PrecheckBlockedException;

/**
 * The start-gate seam used by {@code RunLifecycleService}. Implemented by
 * {@link RunPrecheckService}; tests pass a lambda so lifecycle tests do not
 * need Docker/Prometheus. The gate must run before any transition and before
 * the write-run lock.
 */
public interface RunPrecheckGate {

    /**
     * @return the report when the run may start
     * @throws PrecheckBlockedException when at least one blocking item exists
     */
    PrecheckReport enforce(String runId);
}
