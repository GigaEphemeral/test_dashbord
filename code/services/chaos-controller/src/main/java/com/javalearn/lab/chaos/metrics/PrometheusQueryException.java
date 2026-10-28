package com.javalearn.lab.chaos.metrics;

/**
 * A Prometheus query failed in a way the aggregation layer must surface as
 * {@link MetricQuality#DEGRADED}: connection refused, timeout, HTTP 4xx/5xx,
 * an {@code error} status envelope or malformed JSON. The message is safe to
 * return to the caller (no credentials are part of a Prometheus URL here).
 */
public class PrometheusQueryException extends RuntimeException {

    public PrometheusQueryException(String message) {
        super(message);
    }

    public PrometheusQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
