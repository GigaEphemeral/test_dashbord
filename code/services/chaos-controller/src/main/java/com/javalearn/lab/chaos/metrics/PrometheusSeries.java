package com.javalearn.lab.chaos.metrics;

import java.util.List;
import java.util.Map;

/** One matrix series: labels as returned by Prometheus and ascending points. */
public record PrometheusSeries(Map<String, String> labels, List<PrometheusPoint> points) {
}
