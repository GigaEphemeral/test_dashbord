package com.javalearn.lab.chaos.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.BenchThresholdService;
import com.javalearn.lab.chaos.bench.BenchThresholdService.DurationSummary;
import com.javalearn.lab.chaos.bench.BenchThresholdService.K6Summary;
import com.javalearn.lab.chaos.bench.BenchThresholdService.ThresholdResult;
import com.javalearn.lab.chaos.error.BenchReportNotFoundException;
import com.javalearn.lab.chaos.instance.InstanceConfigProperties;
import com.javalearn.lab.chaos.instance.InstanceConfigRepository;
import com.javalearn.lab.chaos.instance.InstanceConfigStatus;
import com.javalearn.lab.chaos.instance.StoredInstanceConfig;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import com.javalearn.lab.chaos.metrics.MetricsService;
import com.javalearn.lab.chaos.metrics.MetricsViews.MetricsQueryView;
import com.javalearn.lab.chaos.report.RunReportViews.RunReport;
import com.javalearn.lab.chaos.traffic.InstanceStatsService;
import com.javalearn.lab.chaos.traffic.InstanceStatsViews;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunReportServiceTest {

    private static final String RUN = "run-1";
    private static final Instant START = Instant.parse("2026-10-29T10:00:00Z");
    private static final Instant END = Instant.parse("2026-10-29T10:02:00Z");

    private RunLedgerRepository repository;
    private MetricsService metrics;
    private InstanceStatsService stats;
    private BenchThresholdService thresholds;
    private InstanceConfigRepository configs;
    private RunReportService service;

    @BeforeEach
    void setUp() {
        repository = mock(RunLedgerRepository.class);
        metrics = mock(MetricsService.class);
        stats = mock(InstanceStatsService.class);
        thresholds = mock(BenchThresholdService.class);
        configs = mock(InstanceConfigRepository.class);
        InstanceConfigProperties properties = new InstanceConfigProperties();
        service = new RunReportService(repository, metrics, stats, thresholds, configs, properties,
                new ObjectMapper(), Clock.fixed(END, ZoneOffset.UTC));
        when(metrics.requireRun(RUN)).thenReturn(run(RunStatus.COMPLETED, START, END));
        when(metrics.queryRunReadOnly(RUN)).thenReturn(kpi("OK"));
        when(stats.query(RUN)).thenReturn(instanceStats());
    }

    private static RunRecord run(RunStatus status, Instant start, Instant end) {
        return new RunRecord(RUN, "load", "基线", "app-1,app-2", status, "abc", "tester",
                start.minusSeconds(60), end, start, end, false);
    }

    private static MetricsQueryView kpi(String quality) {
        return new MetricsQueryView("run", null, "report", null, quality, null,
                END.toString(), List.of(), null, List.of());
    }

    private static InstanceStatsViews.InstanceStatsView instanceStats() {
        return new InstanceStatsViews.InstanceStatsView(RUN, START.toString(), END.toString(),
                START.getEpochSecond(), END.getEpochSecond(), "OK", "note", List.of(),
                new InstanceStatsViews.UpstreamView(true, "/etc/javalab/nginx/default.conf", null,
                        Map.of("app-1", 1)), List.of());
    }

    @Test
    void assemblesEverySectionFromTheExistingReadPaths() {
        when(repository.findParams(RUN)).thenReturn(List.of(
                new RunLedgerRepository.RunParamRow("request",
                        "{\"TRAFFIC_MODE\":\"target-instance\",\"TARGET_INSTANCE\":\"app-2\"}",
                        START)));
        when(repository.findEnvs(RUN)).thenReturn(List.of(
                new RunLedgerRepository.RunEnvRow("jl-app-1", "jdk21", "sha256:x", "21.0.1",
                        "{}", "{}", START)));
        when(configs.list()).thenReturn(List.of(new StoredInstanceConfig("app-1", "{}", null,
                InstanceConfigStatus.APPLIED, 27, START, START, null)));
        when(thresholds.summary(RUN)).thenReturn(new K6Summary(true, 100.0, 90.0, 10.0, 5.0, 0.083,
                new DurationSummary(12.0, 10.0, 30.0, 50.0),
                List.of(new ThresholdResult("flow_errors", "rate<0.01", true)), null));

        RunReport report = service.report(RUN);

        assertThat(report.reportVersion()).isEqualTo(1);
        assertThat(report.repeatVerified()).isFalse();
        assertThat(report.note()).contains("未重复验证");
        assertThat(report.run().durationSeconds()).isEqualTo(120);
        assertThat(report.run().trafficMode()).isEqualTo("target-instance");
        assertThat(report.run().targetInstance()).isEqualTo("app-2");
        assertThat(report.environment()).hasSize(1);
        assertThat(report.environment().get(0).instance()).isEqualTo("app-1");
        assertThat(report.environment().get(0).configRevision()).isEqualTo(27);
        assertThat(report.environment().get(0).configStatus()).isEqualTo("APPLIED");
        assertThat(report.k6().available()).isTrue();
        assertThat(report.k6().requests()).isEqualTo(100.0);
        assertThat(report.quality().k6Report()).isEqualTo("OK");
        assertThat(report.quality().metrics()).isEqualTo("OK");
        assertThat(report.quality().missing()).isEmpty();
    }

    @Test
    void missingSummaryAndEnvironmentAreReportedNotZero() {
        when(repository.findParams(RUN)).thenReturn(List.of());
        when(repository.findEnvs(RUN)).thenReturn(List.of());
        when(configs.list()).thenReturn(List.of());
        when(thresholds.summary(RUN)).thenThrow(new BenchReportNotFoundException("no report"));
        when(metrics.queryRunReadOnly(RUN)).thenReturn(kpi("NO_DATA"));

        RunReport report = service.report(RUN);

        assertThat(report.run().trafficMode()).isEqualTo("via-nginx");
        // lab_run_env is not captured yet: the fallback still names every node,
        // with null fields and an explicit missing entry.
        assertThat(report.environment()).hasSize(2);
        assertThat(report.environment()).allSatisfy(row -> {
            assertThat(row.imageTag()).isNull();
            assertThat(row.configRevision()).isNull();
        });
        assertThat(report.k6().available()).isFalse();
        assertThat(report.k6().requests()).isNull();
        assertThat(report.quality().k6Report()).isEqualTo("NO_SUMMARY");
        assertThat(report.quality().metrics()).isEqualTo("NO_DATA");
        assertThat(report.quality().missing())
                .anyMatch(item -> item.contains("k6 summary"))
                .anyMatch(item -> item.contains("lab_run_env"))
                .anyMatch(item -> item.contains("no environment record"));
    }
}
