package com.javalearn.lab.chaos.metrics;

import java.util.List;
import java.util.Map;

/**
 * REST payloads for the metrics-aggregation layer (B1-03). Times in the window
 * and in the deep links are documented as UTC ISO-8601 plus epoch seconds;
 * Grafana/Tempo links carry epoch milliseconds/seconds respectively.
 */
public final class MetricsViews {

    private MetricsViews() {
    }

    public record WindowView(String from, String to, long fromEpochSeconds, long toEpochSeconds,
                             long stepSeconds, String range) {
    }

    public record StatsView(Double last, Double avg, Double min, Double max, int samples) {
    }

    /** Points are {@code [epochSeconds, value]}; labels never carry URL/trace ids. */
    public record SeriesView(Map<String, String> labels, List<double[]> points, StatsView stats) {
    }

    public record MetricView(String key, String label, String unit, String promql,
                             boolean groupByInstance, String quality, String reason,
                             List<SeriesView> series) {
    }

    public record LinksView(String grafanaUrl, String tempoUrl) {
    }

    public record MetricsQueryView(String source, String runId, String phase, WindowView window,
                                   String quality, String reason, String generatedAt,
                                   List<MetricView> metrics, LinksView links,
                                   List<String> warnings) {
    }
}
