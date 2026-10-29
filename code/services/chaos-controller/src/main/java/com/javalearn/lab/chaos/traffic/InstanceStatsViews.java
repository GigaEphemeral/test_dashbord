package com.javalearn.lab.chaos.traffic;

import java.util.List;
import java.util.Map;

/**
 * Stage2 B5-02 wire shapes of {@code GET /lab/runs/{runId}/instance-stats}.
 *
 * <p>Every numeric field is nullable: {@code null} means "no series in the
 * window" and is rendered as 无数据, never as 0. {@code sharePercent} is the
 * instance's business hits divided by the sum across rows.</p>
 */
public final class InstanceStatsViews {

    private InstanceStatsViews() {
    }

    /**
     * @param quality  OK when at least one instance reported hits, NO_DATA when
     *                 nothing was scraped, DEGRADED when a query failed
     * @param note     counting rules (actuator excluded, upstream counters
     *                 unavailable) - always present so the UI never has to guess
     */
    public record InstanceStatsView(
            String runId,
            String from,
            String to,
            long fromEpochSeconds,
            long toEpochSeconds,
            String quality,
            String note,
            List<InstanceRow> instances,
            UpstreamView upstream,
            List<String> warnings) {
    }

    /**
     * @param enabled              the run's node selection contains this instance
     * @param hits                 increase of business HTTP requests, actuator excluded
     * @param sharePercent         hits / sum(hits) * 100
     * @param success              status 2xx increase
     * @param failed               status 5xx increase
     * @param avgMs                application-side average handling time
     * @param p95Ms/p99Ms          histogram quantiles; null when the bucket series is absent
     * @param errorRate            failed / (success + failed), 0..1
     * @param rejected             business executor rejected tasks
     * @param weight               nginx upstream weight (null when the config is unavailable)
     * @param expectedSharePercent weight / sum(weights) * 100
     * @param configLabel          stored instance config summary (null when unconfigured)
     */
    public record InstanceRow(
            String instance,
            boolean enabled,
            Double hits,
            Double sharePercent,
            Double success,
            Double failed,
            Double avgMs,
            Double p95Ms,
            Double p99Ms,
            Double rps,
            Double errorRate,
            Double rejected,
            Integer weight,
            Double expectedSharePercent,
            String configLabel) {
    }

    /**
     * @param available false when the nginx config could not be read; the reason
     *                  is shown instead of inventing equal weights
     */
    public record UpstreamView(boolean available, String source, String reason,
                               Map<String, Integer> weights) {
    }
}
