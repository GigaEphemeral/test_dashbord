package com.javalearn.lab.chaos.error;

/**
 * A toxic request that cannot be honoured: unknown type, unknown parameter,
 * missing value or a value outside the configured bounds. Rendered as 400 /
 * 40001, matching the other invalid-argument errors of this controller.
 */
public class InvalidToxiproxyRequestException extends RuntimeException {

    public InvalidToxiproxyRequestException(String message) {
        super(message);
    }
}
