package com.javalearn.lab.chaos.action;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of {@code POST /chaos/stop-all}.
 *
 * <p>One button restores four independent layers in a fixed order:
 * container actions + Toxiproxy, background traffic, the thread lab on every
 * app instance, and orphaned k6 run containers (see
 * {@link EnvironmentStopAllService}). Each layer reports its own outcome so a
 * single failing sub-step never hides the rest or turns an already reverted
 * container into an overall failure.</p>
 *
 * @param reverted  actions reverted successfully and cleared
 * @param failed    actions whose revert failed and remain visible as FAILED
 * @param pending   actions whose faulting Docker call was still running
 *                  (APPLYING) when stop-all fired: their TTL task was cancelled
 *                  and their revert (start/SIGCONT) runs the moment the apply
 *                  completes, without waiting for TTL expiry. They are not
 *                  counted as reverted because the container is not restored
 *                  yet when the response is produced.
 * @param toxiproxy network-fault cleanup outcome: stop-all also removes every
 *                  Toxiproxy toxic and re-enables every configured proxy, so a
 *                  demo is recoverable by one button. {@code not-configured}
 *                  only appears through test seams that do not wire the
 *                  network cleaner.
 * @param operationId B1-02 operation contract id; null when the service is
 *                  called directly (tests, internal callers)
 * @param traffic   background-traffic outcome: {@link #TRAFFIC_STOPPED},
 *                  {@link #TRAFFIC_NOT_RUNNING} (the optional traffic-profile
 *                  container does not exist) or {@link #TRAFFIC_FAILED}.
 *                  {@code null} only on results built through a legacy
 *                  constructor (the sub-step was not evaluated).
 * @param threads   thread-lab stop-all outcome per configured app instance:
 *                  {@code ok} or {@code failed}; empty when no instance is
 *                  configured or the result came from a legacy constructor
 * @param k6Stopped k6 containers that accepted a stop (RUNNING/CREATED/PAUSED,
 *                  and RESTARTING)
 * @param k6Cleaned k6 containers that were actually removed
 * @param k6Failed  k6 containers that could not be handled (blank/unsafe
 *                  {@code runId}, stop or cleanup error), or 1 when the k6
 *                  inventory itself could not be read
 */
public record StopAllResult(int reverted, int failed, int pending, NetworkResetReport toxiproxy,
                            String operationId, String traffic, Map<String, String> threads,
                            int k6Stopped, int k6Cleaned, int k6Failed) {

    public static final String TRAFFIC_STOPPED = "stopped";
    public static final String TRAFFIC_NOT_RUNNING = "not-running";
    public static final String TRAFFIC_FAILED = "failed";

    /** The map is response state: copy it so a caller cannot mutate the result. */
    public StopAllResult {
        threads = threads == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(threads));
    }

    /** Compatibility constructor for callers/tests that predate network support. */
    public StopAllResult(int reverted, int failed, int pending) {
        this(reverted, failed, pending, NetworkResetReport.notConfigured(), null, null, null,
                0, 0, 0);
    }

    /** Compatibility constructor for callers/tests that predate B1-02. */
    public StopAllResult(int reverted, int failed, int pending, NetworkResetReport toxiproxy) {
        this(reverted, failed, pending, toxiproxy, null, null, null, 0, 0, 0);
    }

    /**
     * Compatibility constructor for callers/tests that predate B2-04b1. The
     * environment sub-steps were not evaluated, so they stay neutral.
     */
    public StopAllResult(int reverted, int failed, int pending, NetworkResetReport toxiproxy,
                         String operationId) {
        this(reverted, failed, pending, toxiproxy, operationId, null, null, 0, 0, 0);
    }

    /** Copy with the operation id attached by the REST layer. */
    public StopAllResult withOperationId(String operationId) {
        return new StopAllResult(reverted, failed, pending, toxiproxy, operationId, traffic,
                threads, k6Stopped, k6Cleaned, k6Failed);
    }

    /**
     * Copy with the traffic/thread/k6 outcomes merged onto the container +
     * Toxiproxy result produced by {@link ChaosActionService#stopAll()}.
     */
    public StopAllResult withEnvironment(String traffic, Map<String, String> threads,
                                         int k6Stopped, int k6Cleaned, int k6Failed) {
        return new StopAllResult(reverted, failed, pending, toxiproxy, operationId, traffic,
                threads, k6Stopped, k6Cleaned, k6Failed);
    }
}
