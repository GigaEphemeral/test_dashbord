package com.javalearn.lab.chaos.error;

/**
 * The allowed app instance could not be reached (connect/read timeout, DNS,
 * connection refused). Answered with 503/50301.
 */
public class AppUnreachableException extends RuntimeException {

    public AppUnreachableException(String message) {
        super(message);
    }

    public AppUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
