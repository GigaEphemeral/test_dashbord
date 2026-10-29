package com.javalearn.lab.chaos.ledger;

/**
 * B3-04 seam: called by {@link RunLifecycleService} after READY→RUNNING, on a
 * best-effort basis (a failure must never fail the run). The reconciliation
 * layer implements it to persist its baseline snapshot; ledger-only tests and
 * tools use {@link #NONE}.
 */
@FunctionalInterface
public interface RunStartedHook {

    RunStartedHook NONE = runId -> {
    };

    void onStarted(String runId);
}
