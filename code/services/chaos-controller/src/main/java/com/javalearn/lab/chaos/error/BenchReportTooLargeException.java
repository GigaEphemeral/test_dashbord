package com.javalearn.lab.chaos.error;

/**
 * Stage2 B2-02b-1: the k6 summary report exceeds
 * {@code lab.bench.exec.report-max-bytes}. Answers 413/41301: the file exists
 * and is readable, but the download is refused rather than relayed unbounded.
 */
public class BenchReportTooLargeException extends RuntimeException {

    public BenchReportTooLargeException(String message) {
        super(message);
    }
}
