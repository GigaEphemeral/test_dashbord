package com.javalearn.lab.chaos.error;

/**
 * Toxiproxy refused a mutation because the resource already exists or is in a
 * conflicting state (HTTP 409). Rendered as 409 / 40901.
 */
public class ToxiproxyConflictException extends RuntimeException {

    public ToxiproxyConflictException(String message) {
        super(message);
    }
}
