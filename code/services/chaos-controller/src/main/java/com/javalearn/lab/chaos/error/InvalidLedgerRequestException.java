package com.javalearn.lab.chaos.error;

/** Malformed ledger request (scenario, status filter, paging, sensitive payload). */
public class InvalidLedgerRequestException extends RuntimeException {

    public InvalidLedgerRequestException(String message) {
        super(message);
    }
}
