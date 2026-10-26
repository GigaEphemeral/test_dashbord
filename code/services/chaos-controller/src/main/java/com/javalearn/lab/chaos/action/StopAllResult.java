package com.javalearn.lab.chaos.action;

/**
 * Result of {@code POST /chaos/stop-all}.
 *
 * @param reverted actions reverted successfully and cleared
 * @param failed   actions whose revert failed and remain visible as FAILED
 */
public record StopAllResult(int reverted, int failed) {
}
