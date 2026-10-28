package com.javalearn.lab.chaos.metrics;

import java.util.List;

/** A Prometheus matrix answer (one entry per result series). */
public record PrometheusMatrix(List<PrometheusSeries> series) {
}
