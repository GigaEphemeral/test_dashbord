package com.javalearn.lab.chaos.error;

/**
 * The k6 scenario manifest cannot be served: the configured location does not
 * exist, is unreadable, is not valid JSON or breaks the manifest invariants
 * (unknown view name, ui outside bench, params not declared in env, ...).
 *
 * <p>The bench catalog endpoint answers 503/50303 instead of an empty
 * catalog; an empty list would look like a healthy "no scenarios" answer and
 * hide a broken mount.</p>
 */
public class BenchUnavailableException extends RuntimeException {

    public BenchUnavailableException(String message) {
        super(message);
    }

    public BenchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
