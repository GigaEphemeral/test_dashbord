package com.javalearn.lab.chaos.error;

/** Unknown run id; mapped to the existing 404 envelope. */
public class LedgerRunNotFoundException extends RuntimeException {

    public LedgerRunNotFoundException(String message) {
        super(message);
    }
}
