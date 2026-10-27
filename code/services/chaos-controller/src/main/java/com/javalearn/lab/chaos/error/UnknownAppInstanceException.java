package com.javalearn.lab.chaos.error;

/**
 * The requested proxy instance is not in {@code chaos.app-proxy.instances}.
 * Answered with 404/40401 so an unknown (or mistyped) host name reveals nothing
 * about the compose topology.
 */
public class UnknownAppInstanceException extends RuntimeException {

    public UnknownAppInstanceException(String message) {
        super(message);
    }
}
