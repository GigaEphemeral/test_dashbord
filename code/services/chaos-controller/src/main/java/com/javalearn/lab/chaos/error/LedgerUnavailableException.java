package com.javalearn.lab.chaos.error;

/**
 * The ledger cannot answer right now: bootstrap has not reached {@code OK}
 * (MySQL unreachable, migration pending) or a live query failed. Endpoints that
 * need the ledger answer 503/50302 instead of pretending a run exists.
 */
public class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(String message) {
        super(message);
    }
}
