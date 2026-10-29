package com.javalearn.lab.chaos.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.javalearn.lab.chaos.bench.BenchThresholdService.K6Summary;
import com.javalearn.lab.chaos.metrics.MetricsViews.MetricsQueryView;
import com.javalearn.lab.chaos.traffic.InstanceStatsViews.InstanceStatsView;

import java.util.List;

/**
 * B5-03 single-run report. One document per run, meant to be exported as JSON
 * and compared by hand across manual repetitions.
 *
 * <p>Nothing here is derived by guessing: a missing report/series is
 * {@code null} plus an entry in {@code quality.missing}.</p>
 */
public final class RunReportViews {

    private RunReportViews() {
    }

    public record RunReport(int reportVersion, String generatedAt, boolean repeatVerified,
                            String note, RunSection run, List<EnvironmentSection> environment,
                            K6Summary k6, InstanceStatsView instances, MetricsQueryView kpi,
                            QualitySection quality) {
    }

    public record RunSection(String runId, String name, String scenario, List<String> nodes,
                             String status, String startedAt, String endedAt, Long durationSeconds,
                             String trafficMode, String targetInstance, String sourceCommit,
                             String operator) {
    }

    public record EnvironmentSection(String instance, String imageTag, String imageId,
                                     String javaVersion, JsonNode jvmArgs, JsonNode gc,
                                     Integer configRevision, String configStatus,
                                     String capturedAt) {
    }

    /**
     * @param k6Report OK / NO_THRESHOLDS / NO_SUMMARY / UNAVAILABLE
     * @param metrics  the KPI window quality (OK / NO_DATA / COLLECTING / DEGRADED)
     * @param missing  human-readable gaps; an empty list is the only "complete"
     */
    public record QualitySection(String k6Report, String metrics, List<String> missing) {
    }
}
