package com.javalearn.lab.chaos.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.BenchThresholdService;
import com.javalearn.lab.chaos.bench.BenchThresholdService.K6Summary;
import com.javalearn.lab.chaos.error.BenchReportNotFoundException;
import com.javalearn.lab.chaos.error.BenchReportTooLargeException;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import com.javalearn.lab.chaos.instance.InstanceConfigProperties;
import com.javalearn.lab.chaos.instance.InstanceConfigRepository;
import com.javalearn.lab.chaos.instance.StoredInstanceConfig;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.metrics.MetricsService;
import com.javalearn.lab.chaos.metrics.MetricsViews.MetricsQueryView;
import com.javalearn.lab.chaos.report.RunReportViews.EnvironmentSection;
import com.javalearn.lab.chaos.report.RunReportViews.QualitySection;
import com.javalearn.lab.chaos.report.RunReportViews.RunReport;
import com.javalearn.lab.chaos.report.RunReportViews.RunSection;
import com.javalearn.lab.chaos.traffic.InstanceStatsService;
import com.javalearn.lab.chaos.traffic.InstanceStatsViews.InstanceStatsView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B5-03 report assembly. Every section reuses an existing read path instead of
 * building a second query layer:
 *
 * <ul>
 *   <li>run + params: {@link RunLedgerRepository};</li>
 *   <li>environment: {@code lab_run_env} (captured at start) + stored config revision;</li>
 *   <li>k6: the bounded {@code summary.json} parser (B5-04b);</li>
 *   <li>instances: the B5-02 split service;</li>
 *   <li>KPI: the catalog window in read-only mode (no ledger write).</li>
 * </ul>
 */
public class RunReportService {

    private static final Logger log = LoggerFactory.getLogger(RunReportService.class);

    private static final String VIA_NGINX = "via-nginx";
    private static final int REPORT_VERSION = 1;

    private final RunLedgerRepository repository;
    private final MetricsService metricsService;
    private final InstanceStatsService instanceStats;
    private final BenchThresholdService thresholds;
    private final InstanceConfigRepository configRepository;
    private final InstanceConfigProperties configProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RunReportService(RunLedgerRepository repository, MetricsService metricsService,
                            InstanceStatsService instanceStats, BenchThresholdService thresholds,
                            InstanceConfigRepository configRepository,
                            InstanceConfigProperties configProperties,
                            ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.metricsService = metricsService;
        this.instanceStats = instanceStats;
        this.thresholds = thresholds;
        this.configRepository = configRepository;
        this.configProperties = configProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public RunReport report(String runId) {
        RunRecord run = metricsService.requireRun(runId);
        List<String> missing = new ArrayList<>();
        Map<String, String> groups = paramGroups(runId);
        String trafficMode = paramText(groups.get("request"), "TRAFFIC_MODE");
        String target = paramText(groups.get("request"), "TARGET_INSTANCE");
        if (trafficMode == null) {
            trafficMode = VIA_NGINX;
        }

        List<EnvironmentSection> environment = environment(run, missing);
        K6Summary k6 = k6Summary(runId, missing);
        InstanceStatsView instances = instanceStats.query(runId);
        MetricsQueryView kpi = metricsService.queryRunReadOnly(runId);

        Long durationSeconds = null;
        if (run.startedAt() != null && run.endedAt() != null) {
            durationSeconds = Math.max(0,
                    Duration.between(run.startedAt(), run.endedAt()).getSeconds());
        }
        String k6Quality = !k6.available() ? "NO_SUMMARY"
                : (k6.note() == null ? "OK" : "NO_THRESHOLDS");
        if (!k6.available() && !missing.contains("k6 summary not generated")) {
            missing.add("k6 summary not generated");
        }
        return new RunReport(REPORT_VERSION, clock.instant().toString(),
                false, "单次结果为演示性数据，未重复验证",
                new RunSection(run.runId(), run.name(), run.scenario(), run.nodeList(),
                        run.status().name(), iso(run.startedAt()), iso(run.endedAt()),
                        durationSeconds, trafficMode, target, run.sourceCommit(), run.operator()),
                environment, k6, instances, kpi,
                new QualitySection(k6Quality, kpi.quality(), List.copyOf(missing)));
    }

    private Map<String, String> paramGroups(String runId) {
        Map<String, String> groups = new HashMap<>();
        repository.findParams(runId).forEach(row -> groups.put(row.groupName(), row.payloadJson()));
        return groups;
    }

    private String paramText(String json, String key) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json).path(key);
            return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
        } catch (Exception e) {
            log.warn("run params are not valid JSON: {}", e.toString());
            return null;
        }
    }

    private List<EnvironmentSection> environment(RunRecord run, List<String> missing) {
        Map<String, StoredInstanceConfig> configs = new HashMap<>();
        try {
            configRepository.list().forEach(row -> configs.put(row.instance(), row));
        } catch (RuntimeException e) {
            log.warn("report environment: instance config unreadable: {}", e.toString());
            missing.add("instance config unreadable");
        }
        String prefix = configProperties.getContainerPrefix();
        List<EnvironmentSection> rows = new ArrayList<>();
        for (RunLedgerRepository.RunEnvRow env : repository.findEnvs(run.runId())) {
            String instance = env.containerName() != null && prefix != null
                    && env.containerName().startsWith(prefix)
                    ? env.containerName().substring(prefix.length()) : env.containerName();
            StoredInstanceConfig config = configs.get(instance);
            rows.add(new EnvironmentSection(instance, env.imageTag(), env.imageId(),
                    env.javaVersion(), json(env.jvmArgsJson()), json(env.gcJson()),
                    config == null ? null : config.revision(),
                    config == null ? null : config.status().name(),
                    iso(env.capturedAt())));
        }
        if (rows.isEmpty()) {
            // lab_run_env is not captured yet (B5-04 gap): fall back to the
            // applied instance-config rows so the report still names the image
            // and revision, and mark every node without a row instead of
            // silently dropping it.
            missing.add("lab_run_env not captured; environment comes from the instance config ledger");
            for (String node : run.nodeList()) {
                StoredInstanceConfig config = configs.get(node);
                if (config == null) {
                    missing.add(node + " has no environment record");
                    rows.add(new EnvironmentSection(node, null, null, null, null, null,
                            null, null, null));
                    continue;
                }
                rows.add(new EnvironmentSection(node, config.appliedImageTag(), config.appliedImageId(),
                        null, null, null, config.revision(), config.status().name(),
                        iso(config.appliedAt())));
            }
        }
        return rows;
    }

    private K6Summary k6Summary(String runId, List<String> missing) {
        try {
            return thresholds.summary(runId);
        } catch (BenchReportNotFoundException e) {
            log.debug("report k6: no summary for {}: {}", runId, e.getMessage());
            return unavailableSummary();
        } catch (BenchReportTooLargeException e) {
            missing.add("k6 summary exceeds the byte cap");
            return unavailableSummary();
        } catch (BenchUnavailableException e) {
            missing.add("k6 summary unreadable: " + e.getMessage());
            return unavailableSummary();
        }
    }

    private static K6Summary unavailableSummary() {
        return new K6Summary(false, null, null, null, null, null, null, List.of(), "NO_SUMMARY");
    }

    private JsonNode json(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String iso(Instant value) {
        return value == null ? null : value.toString();
    }
}
