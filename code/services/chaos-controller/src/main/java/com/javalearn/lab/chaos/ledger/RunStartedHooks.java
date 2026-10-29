package com.javalearn.lab.chaos.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Fan-out for {@link RunStartedHook}: every hook runs, one failing hook never
 * prevents the others (or the run start). Used to add the B5 run-environment
 * snapshot next to the B3-04 reconciliation baseline.
 */
public final class RunStartedHooks {

    private static final Logger log = LoggerFactory.getLogger(RunStartedHooks.class);

    private RunStartedHooks() {
    }

    public static RunStartedHook composite(List<RunStartedHook> hooks) {
        List<RunStartedHook> safe = hooks == null ? List.of() : List.copyOf(hooks);
        if (safe.isEmpty()) {
            return RunStartedHook.NONE;
        }
        return runId -> {
            for (RunStartedHook hook : safe) {
                try {
                    hook.onStarted(runId);
                } catch (RuntimeException e) {
                    log.warn("run-started hook {} failed for {}: {}",
                            hook.getClass().getSimpleName(), runId, e.toString());
                }
            }
        };
    }
}
