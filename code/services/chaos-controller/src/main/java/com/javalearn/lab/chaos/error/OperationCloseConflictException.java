package com.javalearn.lab.chaos.error;

/**
 * B2-04a: the operation cannot be closed as requested because it is no longer
 * UNKNOWN (or a concurrent close already gave it the other terminal verdict).
 * Mapped to HTTP 409 with its own code {@code 40905}, so the console can say
 * "already closed / not closeable" instead of a bare conflict.
 */
public class OperationCloseConflictException extends RuntimeException {

    public OperationCloseConflictException(String message) {
        super(message);
    }
}
