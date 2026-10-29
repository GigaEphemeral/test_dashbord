package com.javalearn.lab.chaos.bench;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.exec.BenchReportContent;
import com.javalearn.lab.chaos.bench.exec.BenchRunExecutor;
import com.javalearn.lab.chaos.error.BenchUnavailableException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage2 B5-04b: parses the existing bounded {@code summary.json} so the
 * console can show threshold verdicts and the avg/med latency that Prometheus
 * does not export. This class never invents a value:
 *
 * <ul>
 *   <li>no report file - {@link BenchRunExecutor#report(String)} answers 404;</li>
 *   <li>report over the byte cap - 413, propagated unchanged;</li>
 *   <li>a metric without {@code thresholds} contributes nothing;</li>
 *   <li>a missing {@code avg}/{@code med} stays {@code null} (never 0);</li>
 *   <li>no threshold at all - {@code note=NO_THRESHOLDS}, never PASS.</li>
 * </ul>
 *
 * <p>k6 1.3 writes each metric's {@code thresholds} as an object
 * {@code {"<condition>": <crossed>}}; {@code crossed=true} means the threshold
 * was crossed, i.e. FAILED. The older defensive array shape is also accepted.</p>
 */
public class BenchThresholdService {

    private final ObjectMapper objectMapper;
    private final BenchRunExecutor executor;

    public BenchThresholdService(ObjectMapper objectMapper, BenchRunExecutor executor) {
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public ThresholdReport thresholds(String runId) {
        JsonNode metrics = read(runId).path("metrics");
        List<ThresholdResult> thresholds = collect(metrics);
        return new ThresholdReport(runId, true, List.copyOf(thresholds),
                new MetricsView(
                        duration(metrics.path("http_req_duration")),
                        duration(metrics.path("iteration_duration"))),
                thresholds.isEmpty() ? "NO_THRESHOLDS" : null);
    }

    /**
     * B5-03: the comparison-relevant subset of the same summary (requests,
     * iterations, VU peak, RPS, failed rate, avg/med/p95/p99 and thresholds).
     * Missing numbers stay null - a report never invents a 0.
     */
    public K6Summary summary(String runId) {
        JsonNode metrics = read(runId).path("metrics");
        List<ThresholdResult> thresholds = collect(metrics);
        JsonNode requests = metrics.path("http_reqs");
        JsonNode duration = metrics.path("http_req_duration");
        return new K6Summary(true,
                number(requests.get("count")), number(metrics.path("iterations").get("count")),
                number(metrics.path("vus_max").get("value")),
                number(requests.get("rate")),
                // k6 1.3 writes Rate metrics as {passes, fails, value}; older
                // summaries used "rate". Accept both, never invent a number.
                number(metrics.path("http_req_failed").has("value")
                        ? metrics.path("http_req_failed").get("value")
                        : metrics.path("http_req_failed").get("rate")),
                new DurationSummary(number(duration.get("avg")), number(duration.get("med")),
                        number(duration.get("p(95)")), number(duration.get("p(99)"))),
                List.copyOf(thresholds), thresholds.isEmpty() ? "NO_THRESHOLDS" : null);
    }

    private JsonNode read(String runId) {
        BenchReportContent content = executor.report(runId);
        try {
            return objectMapper.readTree(content.bytes());
        } catch (IOException e) {
            throw new BenchUnavailableException("summary report for run '" + runId
                    + "' is not valid JSON: " + e.getMessage(), e);
        }
    }

    private List<ThresholdResult> collect(JsonNode metrics) {
        List<ThresholdResult> thresholds = new ArrayList<>();
        if (metrics.isObject()) {
            metrics.fields().forEachRemaining(entry ->
                    collectThresholds(entry.getKey(), entry.getValue(), thresholds));
        }
        return thresholds;
    }

    private static void collectThresholds(String metric, JsonNode metricNode,
                                          List<ThresholdResult> target) {
        JsonNode declared = metricNode.path("thresholds");
        if (declared.isObject()) {
            declared.fields().forEachRemaining(entry -> {
                Boolean crossed = crossedValue(entry.getValue());
                if (crossed != null) {
                    target.add(new ThresholdResult(metric, entry.getKey(), !crossed));
                }
            });
        } else if (declared.isArray()) {
            // Older/alternate k6 summary shape: [{threshold, ok|crossed}].
            for (JsonNode item : declared) {
                String condition = item.path("threshold").asText(null);
                if (condition == null) {
                    condition = item.path("condition").asText(null);
                }
                Boolean crossed = crossedValue(item);
                if (condition != null && crossed != null) {
                    target.add(new ThresholdResult(metric, condition, !crossed));
                }
            }
        }
    }

    /** {@code true} = crossed/failed; {@code null} = unparseable, so skip it. */
    private static Boolean crossedValue(JsonNode node) {
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isObject()) {
            if (node.has("crossed") && node.get("crossed").isBoolean()) {
                return node.get("crossed").booleanValue();
            }
            if (node.has("ok") && node.get("ok").isBoolean()) {
                return !node.get("ok").booleanValue();
            }
        }
        return null;
    }

    private static DurationStats duration(JsonNode node) {
        if (!node.isObject()) {
            return new DurationStats(null, null);
        }
        return new DurationStats(number(node.get("avg")), number(node.get("med")));
    }

    private static Double number(JsonNode node) {
        return node != null && node.isNumber() ? node.doubleValue() : null;
    }

    /** One parsed threshold. {@code passed} is {@code !crossed}. */
    public record ThresholdResult(String metric, String condition, boolean passed) {
    }

    /** k6 summary timings in milliseconds; a missing metric stays null. */
    public record MetricsView(
            @JsonProperty("http_req_duration") DurationStats httpReqDuration,
            @JsonProperty("iteration_duration") DurationStats iterationDuration) {
    }

    public record DurationStats(Double avg, Double med) {
    }

    /** B5-03 summary timings in milliseconds; missing quantiles stay null. */
    public record DurationSummary(Double avg, Double med, Double p95, Double p99) {
    }

    /** B5-03 report section; {@code available=true} means the summary was read. */
    public record K6Summary(boolean available, Double requests, Double iterations, Double vusMax,
                            Double rps, Double failedRate, DurationSummary duration,
                            List<ThresholdResult> thresholds, String note) {
    }

    /**
     * @param available  the report file was read (the endpoint is only reached
     *                   when it exists; a missing report is a 404 before this)
     * @param note       {@code NO_THRESHOLDS} when the scenario declared none,
     *                   otherwise {@code null}
     */
    public record ThresholdReport(String runId, boolean available,
                                  List<ThresholdResult> thresholds, MetricsView metrics,
                                  String note) {
    }
}
