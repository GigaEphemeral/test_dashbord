package com.javalearn.lab.chaos.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.instance.InstanceConfig;
import com.javalearn.lab.chaos.instance.InstanceConfigRepository;
import com.javalearn.lab.chaos.instance.StoredInstanceConfig;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.metrics.MetricsService;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.metrics.PrometheusMatrix;
import com.javalearn.lab.chaos.metrics.PrometheusSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage2 B5-02 per-instance traffic split over one run window (design §9.6).
 *
 * <p>Counting rules that the UI repeats verbatim:</p>
 * <ul>
 *   <li>hits are application-side {@code http_server_requests_seconds_count}
 *       increases with {@code /actuator/**} excluded - probe traffic is not a
 *       business hit;</li>
 *   <li>avg/P95/P99 are application handling time, not nginx upstream time;</li>
 *   <li>nginx stub_status exposes no per-peer counters, so the upstream panel
 *       shows weights and an explicit "no cross-check" reason, never a fake 0;</li>
 *   <li>a missing series stays {@code null} (无数据).</li>
 * </ul>
 */
public class InstanceStatsService {

    private static final Logger log = LoggerFactory.getLogger(InstanceStatsService.class);

    private final MetricsService metricsService;
    private final PrometheusGateway gateway;
    private final TrafficSplitProperties properties;
    private final InstanceConfigRepository configRepository;
    private final ObjectMapper objectMapper;
    private final String appJobSelector;
    private final String businessSelector;

    public InstanceStatsService(MetricsService metricsService, PrometheusGateway gateway,
                                TrafficSplitProperties properties,
                                InstanceConfigRepository configRepository,
                                ObjectMapper objectMapper) {
        this.metricsService = metricsService;
        this.gateway = gateway;
        this.properties = properties;
        this.configRepository = configRepository;
        this.objectMapper = objectMapper;
        this.appJobSelector = "job=\"" + properties.getAppJob() + "\"";
        this.businessSelector = appJobSelector + ",uri!~\""
                + properties.getBusinessUriFilter() + "\"";
    }

    public InstanceStatsViews.InstanceStatsView query(String runId) {
        RunRecord run = metricsService.requireRun(runId);
        MetricsService.RunWindow window = metricsService.runWindow(run);
        long from = window.from().getEpochSecond();
        long to = window.to().getEpochSecond();
        String range = Math.max(1, to - from) + "s";
        List<String> queryFailures = new ArrayList<>();

        Map<String, Double> hits = queryByInstance(
                "sum by (instance) (increase(http_server_requests_seconds_count{" + businessSelector + "}[" + range + "]))",
                to, queryFailures, "命中数");
        Map<String, Double> success = queryByInstance(
                "sum by (instance) (increase(http_server_requests_seconds_count{" + businessSelector
                        + ",status=~\"2..\"}[" + range + "]))",
                to, queryFailures, "成功数");
        Map<String, Double> failed = queryByInstance(
                "sum by (instance) (increase(http_server_requests_seconds_count{" + businessSelector
                        + ",status=~\"5..\"}[" + range + "]))",
                to, queryFailures, "失败数");
        Map<String, Double> avg = queryByInstance(
                "sum by (instance) (rate(http_server_requests_seconds_sum{" + businessSelector + "}[" + range + "]))"
                        + " / sum by (instance) (rate(http_server_requests_seconds_count{" + businessSelector
                        + "}[" + range + "]))",
                to, queryFailures, "平均响应");
        Map<String, Double> p95 = queryByInstance(
                "histogram_quantile(0.95, sum by (instance, le) (rate(http_server_requests_seconds_bucket{"
                        + businessSelector + "}[" + range + "])))",
                to, queryFailures, "P95");
        Map<String, Double> p99 = queryByInstance(
                "histogram_quantile(0.99, sum by (instance, le) (rate(http_server_requests_seconds_bucket{"
                        + businessSelector + "}[" + range + "])))",
                to, queryFailures, "P99");
        Map<String, Double> rps = queryByInstance(
                "sum by (instance) (rate(http_server_requests_seconds_count{" + businessSelector + "}[" + range + "]))",
                to, queryFailures, "RPS");
        Map<String, Double> rejected = queryByInstance(
                "sum by (instance) (increase(executor_rejected_total{" + appJobSelector + "}[" + range + "]))",
                to, queryFailures, "线程池拒绝");

        List<String> warnings = new ArrayList<>();
        List<String> instances = resolveInstances(run, hits);
        double totalHits = instances.stream()
                .map(hits::get)
                .filter(value -> value != null && value > 0)
                .mapToDouble(Double::doubleValue)
                .sum();

        UpstreamWeights.Result upstream = UpstreamWeights.read(
                Path.of(properties.getNginxUpstreamConf()), properties.getUpstreamName(),
                properties.getMaxUpstreamBytes());
        // Expected share is derived from the whole upstream block, not only the
        // displayed rows: nginx keeps routing to every server it lists even
        // when the run's node selection narrows the analysis.
        double totalWeight = upstream.weights().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        Map<String, String> configLabels = configLabels(new LinkedHashSet<>(instances), warnings);

        List<InstanceStatsViews.InstanceRow> rows = new ArrayList<>(instances.size());
        for (String instance : instances) {
            Double hit = hits.get(instance);
            Double instanceSuccess = success.get(instance);
            Double instanceFailed = failed.get(instance);
            Double weight = upstream.weights().get(instance) == null
                    ? null : (double) upstream.weights().get(instance);
            Double expectedShare = weight != null && totalWeight > 0
                    ? round(weight * 100.0 / totalWeight) : null;
            Double share = totalHits > 0 && hit != null ? round(hit * 100.0 / totalHits) : null;
            Double errorRate = instanceSuccess != null && instanceFailed != null
                    && instanceSuccess + instanceFailed > 0
                    ? round(instanceFailed / (instanceSuccess + instanceFailed)) : null;
            boolean enabled = run.nodeList().isEmpty() || run.nodeList().contains(instance);
            rows.add(new InstanceStatsViews.InstanceRow(instance, enabled,
                    round(hit), share, round(instanceSuccess), round(instanceFailed),
                    millis(avg.get(instance)), millis(p95.get(instance)), millis(p99.get(instance)),
                    round(rps.get(instance)), errorRate, round(rejected.get(instance)),
                    upstream.weights().get(instance), expectedShare, configLabels.get(instance)));
            if (enabled && share != null && expectedShare != null
                    && Math.abs(share - expectedShare) > properties.getImbalanceWarnPoints()) {
                warnings.add(String.format(
                        "%s 实测占比 %.1f%%，与 upstream 权重期望 %.1f%% 相差 %.1f 个百分点"
                                + "（阈值 %.1f），先排除实例健康/节点启停再比较 JVM 性能",
                        instance, share, expectedShare, Math.abs(share - expectedShare),
                        properties.getImbalanceWarnPoints()));
            }
        }

        String quality = queryFailures.isEmpty()
                ? (totalHits > 0 ? "OK" : "NO_DATA")
                : "DEGRADED";
        warnings.addAll(0, queryFailures);
        return new InstanceStatsViews.InstanceStatsView(runId,
                window.from().toString(), window.to().toString(), from, to, quality,
                note(upstream), rows,
                new InstanceStatsViews.UpstreamView(upstream.available(),
                        properties.getNginxUpstreamConf(), upstream.reason(), upstream.weights()),
                warnings);
    }

    /**
     * The run's node selection wins; without one, only instances that actually
     * answered are shown (no fabricated rows for idle instances).
     */
    private List<String> resolveInstances(RunRecord run, Map<String, Double> hits) {
        List<String> selected = run.nodeList();
        if (!selected.isEmpty()) {
            return selected;
        }
        return new ArrayList<>(new java.util.TreeSet<>(hits.keySet()));
    }

    private Map<String, Double> queryByInstance(String expr, long at, List<String> failures,
                                                String label) {
        try {
            PrometheusMatrix matrix = gateway.queryInstant(expr, at);
            Map<String, Double> values = new LinkedHashMap<>();
            for (PrometheusSeries series : matrix.series()) {
                String instance = host(series.labels().get("instance"));
                if (instance == null || series.points().isEmpty()) {
                    continue;
                }
                values.put(instance, series.points().get(series.points().size() - 1).value());
            }
            return values;
        } catch (RuntimeException e) {
            log.warn("instance-stats {} query failed: {}", label, e.toString());
            failures.add(label + " 查询失败：" + e.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> configLabels(Set<String> instances, List<String> warnings) {
        Map<String, String> labels = new HashMap<>();
        try {
            for (StoredInstanceConfig row : configRepository.list()) {
                if (!instances.contains(row.instance())) {
                    continue;
                }
                labels.put(row.instance(), configLabel(row));
            }
        } catch (RuntimeException e) {
            log.warn("instance-stats config readback unavailable: {}", e.toString());
            warnings.add("实例配置不可读（配置组列留空）：" + e.getMessage());
        }
        return labels;
    }

    private String configLabel(StoredInstanceConfig row) {
        String status = row.status().name().toLowerCase() + " rev" + row.revision();
        try {
            InstanceConfig config = objectMapper.readValue(row.configJson(), InstanceConfig.class);
            List<String> parts = new ArrayList<>();
            if (config.imageTag() != null) {
                parts.add(config.imageTag());
            }
            if (config.xmxMb() != null) {
                parts.add("Xmx" + config.xmxMb() + "m");
            }
            if (config.gcCollector() != null) {
                parts.add(config.gcCollector());
            }
            parts.add(status);
            return String.join(" · ", parts);
        } catch (Exception e) {
            return status;
        }
    }

    private String note(UpstreamWeights.Result upstream) {
        String base = "命中数 = run 窗口内应用端 http_server_requests 增量（已排除 /actuator/**）；"
                + "占比 = 该实例命中 / 所选实例命中合计；平均响应与 P95/P99 为应用端处理时间。"
                + "Nginx stub_status 不提供 per-peer 计数，因此只展示 upstream 权重，不做上游交叉核对；"
                + "run 期间如有背景流量，占比会被污染。";
        if (upstream.available()) {
            return base;
        }
        return base + " 权重不可用：" + upstream.reason() + "。";
    }

    private static String host(String instance) {
        if (instance == null) {
            return null;
        }
        int colon = instance.indexOf(':');
        return colon > 0 ? instance.substring(0, colon) : instance;
    }

    private static Double millis(Double seconds) {
        return seconds == null ? null : round(seconds * 1000.0);
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 1000.0) / 1000.0;
    }
}
