package com.javalearn.lab.chaos.error;

/**
 * The caller sent a request body larger than {@code chaos.app-proxy.max-request-bytes}.
 * Answered 413/41301 (Payload Too Large): the request itself is well-formed, it
 * is only over the proxy byte budget, so {@code InvalidAppProxyRequestException}
 * (400) would be the wrong semantics. The console reads the cap from the error
 * message and shrinks the next request.
 */
public class AppProxyRequestTooLargeException extends RuntimeException {

    public AppProxyRequestTooLargeException(String message) {
        super(message);
    }
}
