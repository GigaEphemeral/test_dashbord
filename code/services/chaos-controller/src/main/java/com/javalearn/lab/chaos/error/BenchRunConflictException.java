package com.javalearn.lab.chaos.error;

/**
 * Stage2 B2-02b-1: a k6 run container with the same name already exists and is
 * not in a terminal state (running/created/restarting/paused). The launch is
 * refused with 409/40904 instead of silently replacing a live run; an exited
 * container is removed and recreated by the executor.
 */
public class BenchRunConflictException extends RuntimeException {

    public BenchRunConflictException(String message) {
        super(message);
    }

    public BenchRunConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
