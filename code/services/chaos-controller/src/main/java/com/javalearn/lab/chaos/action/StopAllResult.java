package com.javalearn.lab.chaos.action;

/**
 * Result of {@code POST /chaos/stop-all}.
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
 */
public record StopAllResult(int reverted, int failed, int pending, NetworkResetReport toxiproxy) {

    /** Compatibility constructor for callers/tests that predate network support. */
    public StopAllResult(int reverted, int failed, int pending) {
        this(reverted, failed, pending, NetworkResetReport.notConfigured());
    }
}
