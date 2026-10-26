package com.javalearn.lab.chaos.error;

/**
 * The Toxiproxy control plane is disabled, unreachable or answered with an
 * unexpected server error. Rendered as 503 / 50301 so the console can tell a
 * missing {@code chaos-proxy} profile apart from a bad request.
 */
public class ToxiproxyUnavailableException extends RuntimeException {

    public ToxiproxyUnavailableException(String message) {
        super(message);
    }

    public ToxiproxyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
