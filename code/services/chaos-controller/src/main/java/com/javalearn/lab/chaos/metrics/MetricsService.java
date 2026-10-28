package com.javalearn.lab.chaos.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.InvalidMetricsRequestException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.javalearn.lab.chaos.metrics.MetricsViews.LinksView;
import static com.javalearn.lab.chaos.metrics.MetricsViews.MetricView;
import static com.javalearn.lab.chaos.metrics.MetricsViews.MetricsQueryView;
import static com.javalearn.lab.chaos.metrics.MetricsViews.SeriesView;
import static com.javalearn.lab.chaos.metrics.MetricsViews.StatsView;
import static com.javalearn.lab.chaos.metrics.MetricsViews.WindowView;

/**
 * Window aggregation on top of the metric catalog. A caller can only name a
 * catalog key; the PromQL, the step and the window bounds stay server-side.
 *
 * <p>Quality rules (A22):</p>
 * <ul>
 *   <li>a Prometheus failure (unreachable/timeout/HTTP error/malformed JSON)
 *       makes that metric {@code DEGRADED} and the response DEGRADED - never a
 *       synthetic zero;</li>
 *   <li>a successful query with no series is {@code NO_DATA}; if the right edge
 *       reaches into the current scrape interval it is {@code COLLECTING}
 *       instead;</li>
 *   <li>real samples are {@code OK}; a cap that drops data is {@code DEGRADED}
 *       for that metric while the remaining series are still returned;</li>
 *   <li>each metric keeps its own quality when another one fails.</li>
 * </ul>
 *
 * <p>When the request names a run, every metric window is written to
 * {@code lab_metric_window} best-effort: a ledger failure logs WARN and leaves
 * the query answer untouched.</p>
 */
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);
    private static final Pattern PHASE = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final String DEFAULT_PHASE = "adhoc";
    private static final Set<String> DENIED_LABELS = Set.of(
            "__name__", "url", "raw_url", "rawurl", "uri_path",
            "traceid", "trace_id", "spanid", "span_id",
            "userid", "user_id", "orderid", "order_id",
            "requestid", "request_id", "idempotencykey", "idempotency_key",
            "sessionid", "session_id", "query");

    private final MetricsProperties properties;
    private final MetricCatalog catalog;
    private final PrometheusGateway gateway;
    private final RunLedgerRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MetricsService(MetricsProperties properties, MetricCatalog catalog,
                          PrometheusGateway gateway, RunLedgerRepository repository,
                          ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.catalog = catalog;
        this.gateway = gateway;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Realtime view without a run context; {@code from} and {@code to} are required. */
    public MetricsQueryView queryWindow(String fromRaw, String toRaw, String keysRaw, String phaseRaw) {
        Instant from = MetricsTime.parse(fromRaw, "from");
        Instant to = MetricsTime.parse(toRaw, "to");
        Window window = Window.of(from, to, properties);
        return execute(window, "window", null, phase(phaseRaw), resolveDefinitions(keysRaw));
    }

    /**
     * Run-context view. Without {@code from}/{@code to} the window is
     * {@code [runStart - scrapeGrace, runEnd + drainGrace]} (a run without an
     * end uses now); supplying both overrides the derived bounds exactly.
     */
    public MetricsQueryView queryRun(String runId, String fromRaw, String toRaw, String keysRaw,
                                     String phaseRaw) {
        RunRecord run;
        try {
            run = repository.findRun(runId)
                    .orElseThrow(() -> new LedgerRunNotFoundException("Run '" + runId + "' was not found"));
        } catch (DataAccessException e) {
            // The window itself needs the run timestamps; unlike the best-effort
            // metric write this lookup cannot be skipped. 503 lets the caller
            // distinguish "ledger down" from "run missing" (404).
            throw new LedgerUnavailableException("ledger unavailable while resolving run '"
                    + runId + "': " + e.getMessage());
        }
        Window window;
        if (fromRaw == null && toRaw == null) {
            Instant start = run.startedAt() != null ? run.startedAt() : run.createdAt();
            Instant end = run.endedAt() != null ? run.endedAt() : clock.instant();
            window = Window.of(start.minusSeconds(properties.getScrapeGraceSeconds()),
                    end.plusSeconds(properties.getDrainGraceSeconds()), properties);
        } else if (fromRaw != null && toRaw != null) {
            window = Window.of(MetricsTime.parse(fromRaw, "from"),
                    MetricsTime.parse(toRaw, "to"), properties);
        } else {
            throw new InvalidMetricsRequestException(
                    "Provide both 'from' and 'to', or neither to use the run window");
        }
        return execute(window, "run", runId, phase(phaseRaw), resolveDefinitions(keysRaw));
    }

    private MetricsQueryView execute(Window window, String source, String runId, String phase,
                                     List<MetricDefinition> definitions) {
        Instant now = clock.instant();
        List<MetricOutcome> outcomes = new ArrayList<>(definitions.size());
        for (MetricDefinition definition : definitions) {
            outcomes.add(evaluate(definition, window, now));
        }
        outcomes = capSeries(outcomes);

        List<MetricView> metrics = new ArrayList<>(outcomes.size());
        for (MetricOutcome outcome : outcomes) {
            metrics.add(new MetricView(outcome.definition().key(), outcome.definition().label(),
                    outcome.definition().unit(), outcome.promql(),
                    outcome.definition().groupByInstance(), outcome.quality().name(),
                    outcome.reason(), outcome.series()));
        }

        MetricQuality overall = overallQuality(outcomes);
        String overallReason = overallReason(overall, outcomes);
        List<String> warnings = new ArrayList<>();
        if (runId != null) {
            persist(runId, phase, window, metrics, warnings);
        }
        return new MetricsQueryView(source, runId, phase,
                new WindowView(MetricsTime.iso(window.from()), MetricsTime.iso(window.to()),
                        window.from().getEpochSecond(), window.to().getEpochSecond(),
                        window.stepSeconds(), window.range()),
                overall.name(), overallReason, MetricsTime.iso(now), metrics,
                buildLinks(window), warnings);
    }

    private MetricOutcome evaluate(MetricDefinition definition, Window window, Instant now) {
        String promql = definition.promql().replace("${range}", window.range());
        try {
            PrometheusMatrix matrix = gateway.queryRange(promql,
                    window.from().getEpochSecond(), window.to().getEpochSecond(), window.stepSeconds());
            SeriesBatch batch = toSeries(matrix);
            if (batch.series().isEmpty()) {
                if (!window.to().isBefore(now.minusSeconds(properties.getScrapeGraceSeconds()))) {
                    return new MetricOutcome(definition, promql, MetricQuality.COLLECTING,
                            "no samples yet: the window reaches into the current scrape interval; "
                                    + "the first usable sample time is unknown - retry after the next scrape "
                                    + "(scrape grace " + properties.getScrapeGraceSeconds() + "s)",
                            List.of());
                }
                return new MetricOutcome(definition, promql, MetricQuality.NO_DATA,
                        "no series returned for the selected window", List.of());
            }
            if (batch.pointsTruncated()) {
                return new MetricOutcome(definition, promql, MetricQuality.DEGRADED,
                        "points truncated at max-points=" + properties.getMaxPoints(),
                        batch.series());
            }
            return new MetricOutcome(definition, promql, MetricQuality.OK, null, batch.series());
        } catch (PrometheusQueryException e) {
            return new MetricOutcome(definition, promql, MetricQuality.DEGRADED, e.getMessage(),
                    List.of());
        }
    }

    /**
     * Converts Prometheus series into response series: filters labels, drops
     * NaN/+Inf samples and series without any finite sample, caps points at
     * {@code max-points} (keeping the newest tail) and computes per-series stats.
     */
    private SeriesBatch toSeries(PrometheusMatrix matrix) {
        List<SeriesView> series = new ArrayList<>();
        boolean truncated = false;
        for (PrometheusSeries raw : matrix.series()) {
            List<double[]> points = new ArrayList<>(raw.points().size());
            for (PrometheusPoint point : raw.points()) {
                if (Double.isFinite(point.timestamp()) && Double.isFinite(point.value())) {
                    points.add(new double[]{point.timestamp(), point.value()});
                }
            }
            if (points.isEmpty()) {
                continue;
            }
            points.sort(Comparator.comparingDouble(point -> point[0]));
            if (points.size() > properties.getMaxPoints()) {
                points = new ArrayList<>(points.subList(points.size() - properties.getMaxPoints(),
                        points.size()));
                truncated = true;
            }
            series.add(new SeriesView(filterLabels(raw.labels()), points, stats(points)));
        }
        return new SeriesBatch(series, truncated);
    }

    private List<MetricOutcome> capSeries(List<MetricOutcome> outcomes) {
        int remaining = properties.getMaxSeries();
        List<MetricOutcome> capped = new ArrayList<>(outcomes.size());
        for (MetricOutcome outcome : outcomes) {
            if (outcome.series().isEmpty()) {
                capped.add(outcome);
                continue;
            }
            if (remaining <= 0) {
                capped.add(new MetricOutcome(outcome.definition(), outcome.promql(),
                        MetricQuality.DEGRADED,
                        "series cap reached at max-series=" + properties.getMaxSeries(),
                        List.of()));
                continue;
            }
            if (outcome.series().size() > remaining) {
                List<SeriesView> kept = List.copyOf(outcome.series().subList(0, remaining));
                remaining = 0;
                capped.add(new MetricOutcome(outcome.definition(), outcome.promql(),
                        MetricQuality.DEGRADED,
                        "series truncated at max-series=" + properties.getMaxSeries(), kept));
                continue;
            }
            remaining -= outcome.series().size();
            capped.add(outcome);
        }
        return capped;
    }

    private static Map<String, String> filterLabels(Map<String, String> raw) {
        Map<String, String> labels = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value == null || value.isEmpty()) {
                return;
            }
            String normalized = key.toLowerCase(Locale.ROOT);
            if (DENIED_LABELS.contains(normalized)) {
                return;
            }
            labels.put(key, value);
        });
        String instance = labels.get("instance");
        if (instance != null && (instance.length() > 128 || instance.contains("://")
                || instance.contains("?") || instance.contains(" "))) {
            // A URL/trace-like value must never masquerade as the instance dimension.
            labels.remove("instance");
        }
        return labels;
    }

    private static StatsView stats(List<double[]> points) {
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double[] point : points) {
            sum += point[1];
            min = Math.min(min, point[1]);
            max = Math.max(max, point[1]);
        }
        int samples = points.size();
        return new StatsView(points.get(samples - 1)[1], sum / samples, min, max, samples);
    }

    private List<MetricDefinition> resolveDefinitions(String keysRaw) {
        if (keysRaw == null || keysRaw.isBlank()) {
            return catalog.definitions();
        }
        Set<String> requested = new LinkedHashSet<>();
        int tokens = 0;
        for (String part : keysRaw.split(",")) {
            String key = part.trim();
            if (!key.isEmpty()) {
                requested.add(key);
                tokens++;
            }
        }
        if (requested.isEmpty()) {
            throw new InvalidMetricsRequestException("'keys' was provided but contains no metric key");
        }
        if (tokens > properties.getMaxKeys()) {
            throw new InvalidMetricsRequestException("At most " + properties.getMaxKeys()
                    + " metric keys may be requested per call (got " + tokens
                    + "); env LAB_METRICS_MAX_KEYS");
        }
        List<MetricDefinition> definitions = new ArrayList<>(requested.size());
        List<String> unknown = new ArrayList<>();
        for (String key : requested) {
            MetricDefinition definition = catalog.get(key);
            if (definition == null) {
                unknown.add(key);
            } else {
                definitions.add(definition);
            }
        }
        if (!unknown.isEmpty()) {
            throw new InvalidMetricsRequestException("Unknown metric key(s): " + String.join(", ", unknown)
                    + "; available keys: " + String.join(", ", catalog.keys()));
        }
        return definitions;
    }

    private static String phase(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PHASE;
        }
        String value = raw.trim();
        if (!PHASE.matcher(value).matches()) {
            throw new InvalidMetricsRequestException("Query parameter 'phase' must match "
                    + PHASE.pattern() + " (was '" + value + "')");
        }
        return value;
    }

    private static MetricQuality overallQuality(List<MetricOutcome> outcomes) {
        boolean degraded = false;
        boolean collecting = false;
        boolean ok = false;
        for (MetricOutcome outcome : outcomes) {
            switch (outcome.quality()) {
                case DEGRADED -> degraded = true;
                case COLLECTING -> collecting = true;
                case OK -> ok = true;
                case NO_DATA -> {
                    // NO_DATA alone must not be reported as OK.
                }
            }
        }
        if (degraded) {
            return MetricQuality.DEGRADED;
        }
        if (collecting) {
            return MetricQuality.COLLECTING;
        }
        if (ok) {
            return MetricQuality.OK;
        }
        return MetricQuality.NO_DATA;
    }

    private static String overallReason(MetricQuality overall, List<MetricOutcome> outcomes) {
        if (overall == MetricQuality.OK) {
            return null;
        }
        if (overall == MetricQuality.NO_DATA) {
            return "no series returned for any requested metric in the selected window";
        }
        List<String> reasons = new ArrayList<>();
        for (MetricOutcome outcome : outcomes) {
            if (outcome.quality() == overall && outcome.reason() != null) {
                reasons.add(outcome.definition().key() + ": " + outcome.reason());
            } else if (overall == MetricQuality.DEGRADED
                    && outcome.quality() == MetricQuality.DEGRADED && outcome.reason() != null) {
                reasons.add(outcome.definition().key() + ": " + outcome.reason());
            }
        }
        if (reasons.isEmpty()) {
            return overall == MetricQuality.COLLECTING
                    ? "waiting for the first scrape inside the window"
                    : "one or more metric queries failed";
        }
        if (reasons.size() > 3) {
            return String.join("; ", reasons.subList(0, 3)) + "; +" + (reasons.size() - 3) + " more";
        }
        return String.join("; ", reasons);
    }

    private LinksView buildLinks(Window window) {
        long fromMillis = window.from().toEpochMilli();
        long toMillis = window.to().toEpochMilli();
        String grafanaBase = stripTrailingSlash(properties.getGrafanaUrl());
        String uid = properties.getGrafanaDashboardUid();
        String grafana = uid == null || uid.isBlank()
                ? grafanaBase + "/explore?from=" + fromMillis + "&to=" + toMillis
                : grafanaBase + "/d/" + uid + "?from=" + fromMillis + "&to=" + toMillis;
        String tempo = stripTrailingSlash(properties.getTempoUrl())
                + "/api/search?start=" + window.from().getEpochSecond()
                + "&end=" + window.to().getEpochSecond();
        return new LinksView(grafana, tempo);
    }

    private void persist(String runId, String phase, Window window, List<MetricView> metrics,
                         List<String> warnings) {
        int failures = 0;
        for (MetricView metric : metrics) {
            try {
                repository.insertMetricWindow(runId, phase, metric.key(), metric.promql(),
                        window.from(), window.to(), statsJson(metric), metric.quality());
            } catch (RuntimeException e) {
                failures++;
                log.warn("Metric window not persisted for run {} key {}: {}",
                        runId, metric.key(), e.toString());
            }
        }
        if (failures > 0) {
            warnings.add("ledger write failed for " + failures
                    + " metric(s); the query result is unaffected");
        }
    }

    private String statsJson(MetricView metric) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seriesCount", metric.series().size());
        payload.put("samples", metric.series().stream()
                .mapToInt(series -> series.stats() == null ? 0 : series.stats().samples()).sum());
        List<Map<String, Object>> series = new ArrayList<>(metric.series().size());
        for (SeriesView item : metric.series()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("labels", item.labels());
            entry.put("stats", item.stats());
            series.add(entry);
        }
        payload.put("series", series);
        if (metric.reason() != null) {
            payload.put("reason", metric.reason());
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"seriesCount\":" + metric.series().size() + ",\"samples\":0}";
        }
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record Window(Instant from, Instant to, long stepSeconds, String range) {

        static Window of(Instant from, Instant to, MetricsProperties properties) {
            long seconds = Duration.between(from, to).getSeconds();
            if (seconds < 1) {
                throw new InvalidMetricsRequestException(
                        "'from' must be at least 1 second before 'to'");
            }
            long step = Math.max(properties.getMinStepSeconds(),
                    (long) Math.ceil(seconds
                            / (double) Math.max(1L, properties.getMaxPoints() - 1L)));
            return new Window(from, to, step, step + "s");
        }
    }

    private record MetricOutcome(MetricDefinition definition, String promql, MetricQuality quality,
                                 String reason, List<SeriesView> series) {
    }

    private record SeriesBatch(List<SeriesView> series, boolean pointsTruncated) {
    }
}
