package com.javalearn.lab.chaos.error;

/**
 * The requested method/path pair is outside the thread-lab allow-list. Answered
 * with 404/40401 (deny-by-default without confirming which internal paths
 * exist); this also covers {@code /lab/**} paths that are not
 * {@code /lab/thread/**}.
 */
public class AppPathNotAllowedException extends RuntimeException {

    public AppPathNotAllowedException(String message) {
        super(message);
    }
}
