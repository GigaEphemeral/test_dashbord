package com.javalearn.lab.chaos.error;

/**
 * The app answered, but not with the {@code Result} JSON the console expects
 * (for example an HTML error page or a gateway page). Answered with 502/50201
 * and a message naming the instance and the observed content type, so the
 * operator can tell an app failure from an unreachable instance.
 */
public class AppResponseNotJsonException extends RuntimeException {

    public AppResponseNotJsonException(String message) {
        super(message);
    }
}
