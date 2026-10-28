package com.javalearn.lab.chaos.metrics;

/**
 * Data-quality verdict for one metric (and, rolled up, for the whole response).
 *
 * <ul>
 *   <li>{@code OK}: at least one real sample was returned.</li>
 *   <li>{@code NO_DATA}: Prometheus answered, but the window has no series. This
 *       is never rendered as a numeric zero.</li>
 *   <li>{@code COLLECTING}: Prometheus answered with no series and the window
 *       reaches into the current scrape interval (right edge close to now); the
 *       first usable sample is not known yet.</li>
 *   <li>{@code DEGRADED}: the query failed (unreachable, timeout, HTTP error,
 *       malformed JSON) or the answer was truncated by a cap. Whatever series
 *       did arrive is still returned.</li>
 * </ul>
 */
public enum MetricQuality {
    OK,
    NO_DATA,
    COLLECTING,
    DEGRADED
}
