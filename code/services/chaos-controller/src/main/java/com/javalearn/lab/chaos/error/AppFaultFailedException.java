package com.javalearn.lab.chaos.error;

/**
 * The app instance answered a thread-lab fault call with a non-2xx status.
 *
 * <p>B2-04b2: the unified {@code /chaos/instances/{name}/thread/**} entry turns
 * that answer into a FAILED operation, because the fault was not applied. The
 * message names the instance, the upstream status and the path, and never
 * carries the internal lab token. Answered with 502/50201.</p>
 */
public class AppFaultFailedException extends RuntimeException {

    public AppFaultFailedException(String message) {
        super(message);
    }
}
