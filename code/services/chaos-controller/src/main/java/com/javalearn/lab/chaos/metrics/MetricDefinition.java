package com.javalearn.lab.chaos.metrics;

/**
 * One catalog entry. The key is the only thing a caller may send; the PromQL
 * stays server-side. {@code ${range}} in {@code promql} is replaced with the
 * computed step (e.g. {@code 30s}) before the query is sent.
 */
public record MetricDefinition(
        String key,
        String label,
        String unit,
        String promql,
        boolean groupByInstance) {
}
