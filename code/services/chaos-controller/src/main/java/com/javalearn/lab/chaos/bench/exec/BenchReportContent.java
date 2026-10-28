package com.javalearn.lab.chaos.bench.exec;

/**
 * Stage2 B2-02b-1: one summary report read from the reports volume.
 *
 * @param fileName attachment name (run id + configured summary file)
 * @param bytes    raw file content, already checked against the byte cap
 */
public record BenchReportContent(String fileName, byte[] bytes) {
}
