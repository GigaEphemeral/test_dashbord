package com.javalearn.lab.chaos.metrics;

/**
 * Read-only Prometheus {@code query_range} boundary. The only implementation
 * talks to the configured Prometheus base URL over the JDK {@link java.net.http.HttpClient}
 * (no extra dependency). Tests provide a real local HTTP server or a lambda.
 */
public interface PrometheusGateway {

    PrometheusMatrix queryRange(String query, long startEpochSeconds, long endEpochSeconds,
                                long stepSeconds);

    /**
     * Instant query used by the B1-04 run pre-check ({@code up}/{@code
     * mysql_cluster_probe_up} samples). The default derives a one-step range
     * query so every existing gateway/fake keeps working; the HTTP
     * implementation calls {@code /api/v1/query} directly.
     *
     * @param atEpochSeconds evaluation instant
     */
    default PrometheusMatrix queryInstant(String query, long atEpochSeconds) {
        return queryRange(query, atEpochSeconds - 1, atEpochSeconds, 1);
    }
}
