package com.javalearn.lab.chaos.action;

/**
 * Result of {@code POST /chaos/stop-all}.
 *
 * @param reverted actions reverted successfully and cleared
 * @param failed   actions whose revert failed and remain visible as FAILED
 * @param pending  actions whose faulting Docker call was still running
 *                 (APPLYING) when stop-all fired: their TTL task was cancelled
 *                 and their revert (start/SIGCONT) runs the moment the apply
 *                 completes, without waiting for TTL expiry. They are not
 *                 counted as reverted because the container is not restored
 *                 yet when the response is produced.
 */
public record StopAllResult(int reverted, int failed, int pending) {
}
