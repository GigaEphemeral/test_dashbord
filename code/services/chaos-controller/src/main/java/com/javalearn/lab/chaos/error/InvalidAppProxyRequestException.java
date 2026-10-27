package com.javalearn.lab.chaos.error;

/**
 * The forwarded request itself is invalid for this narrow proxy (for example a
 * body above {@code chaos.app-proxy.max-request-bytes}). Answered with
 * 400/40001.
 */
public class InvalidAppProxyRequestException extends RuntimeException {

    public InvalidAppProxyRequestException(String message) {
        super(message);
    }
}
