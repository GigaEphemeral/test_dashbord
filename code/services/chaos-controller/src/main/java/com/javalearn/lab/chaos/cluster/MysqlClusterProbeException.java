package com.javalearn.lab.chaos.cluster;

/**
 * The probe ran but its outcome cannot be trusted (timeout, non-zero exit,
 * unparseable or empty membership). The monitor records it as a probe failure
 * and keeps the last successfully published member series.
 */
public class MysqlClusterProbeException extends RuntimeException {

    public MysqlClusterProbeException(String message) {
        super(message);
    }
}
