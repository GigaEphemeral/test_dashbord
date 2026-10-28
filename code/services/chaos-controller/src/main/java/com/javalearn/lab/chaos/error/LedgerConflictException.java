package com.javalearn.lab.chaos.error;

/**
 * The ledger is reachable but the requested state change conflicts with the
 * stored one (e.g. a status transition lost a race, or the daily run-id space is
 * exhausted). Mapped to 409.
 */
public class LedgerConflictException extends RuntimeException {

    public LedgerConflictException(String message) {
        super(message);
    }
}
