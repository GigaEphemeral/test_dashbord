package com.javalearn.lab.chaos.error;

/** Invalid chaos action or TTL (HTTP 400). */
public class InvalidChaosActionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidChaosActionException(String message) {
        super(message);
    }
}
