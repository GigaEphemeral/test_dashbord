package com.javalearn.lab.chaos.error;

/**
 * A proxy or toxic that Toxiproxy (or the configured allow-list) does not know.
 * Rendered as 404 / 40401, matching {@link ContainerNotFoundException}.
 */
public class ToxiproxyNotFoundException extends RuntimeException {

    public ToxiproxyNotFoundException(String message) {
        super(message);
    }
}
