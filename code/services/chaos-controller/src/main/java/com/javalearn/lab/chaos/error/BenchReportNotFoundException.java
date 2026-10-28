package com.javalearn.lab.chaos.error;

/**
 * Stage2 B2-02b-1: the k6 summary report for a run is not on disk yet (k6 has
 * not exited, or the scenario failed before writing it). Answers 404 - the
 * console can distinguish "not ready" from a server failure.
 */
public class BenchReportNotFoundException extends RuntimeException {

    public BenchReportNotFoundException(String message) {
        super(message);
    }
}
