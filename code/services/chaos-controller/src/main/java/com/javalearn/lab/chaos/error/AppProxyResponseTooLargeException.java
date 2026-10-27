package com.javalearn.lab.chaos.error;

/**
 * The app answered with a body larger than {@code chaos.app-proxy.max-response-bytes}.
 * Answered 502/50202, next to {@link AppResponseNotJsonException}: truncating
 * would break the {@code Result} JSON envelope, so the proxy refuses to relay
 * rather than hand the console a half document. The message names the instance,
 * the path and the configured cap; the upstream body itself is never included.
 */
public class AppProxyResponseTooLargeException extends RuntimeException {

    public AppProxyResponseTooLargeException(String message) {
        super(message);
    }
}
