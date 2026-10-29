package com.javalearn.lab.chaos.error;

/**
 * B3-04: the run exists but no reconciliation result was ever persisted
 * (HTTP 404, message starts with {@code NO_DATA}). Distinct from a missing run
 * so the console can show "尚未对账" instead of "run 不存在".
 */
public class ReconciliationNotFoundException extends RuntimeException {

    public ReconciliationNotFoundException(String message) {
        super(message);
    }
}
