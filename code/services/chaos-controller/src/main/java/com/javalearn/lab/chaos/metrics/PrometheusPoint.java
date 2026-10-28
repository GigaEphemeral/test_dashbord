package com.javalearn.lab.chaos.metrics;

/** One sample: epoch seconds (may carry milliseconds) and a numeric value. */
public record PrometheusPoint(double timestamp, double value) {
}
