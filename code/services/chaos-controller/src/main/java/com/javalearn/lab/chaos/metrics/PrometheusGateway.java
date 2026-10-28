package com.javalearn.lab.chaos.metrics;

/**
 * Read-only Prometheus {@code query_range} boundary. The only implementation
 * talks to the configured Prometheus base URL over the JDK {@link java.net.http.HttpClient}
 * (no extra dependency). Tests provide a real local HTTP server or a lambda.
 */
public interface PrometheusGateway {

    PrometheusMatrix queryRange(String query, long startEpochSeconds, long endEpochSeconds,
                                long stepSeconds);
}
