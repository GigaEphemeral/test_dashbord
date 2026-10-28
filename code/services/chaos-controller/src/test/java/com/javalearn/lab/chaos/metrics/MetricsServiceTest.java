package com.javalearn.lab.chaos.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.InvalidMetricsRequestException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Aggregation semantics without a live Prometheus: a lambda gateway returns
 * canned matrices (or throws the same exception the HTTP gateway throws) so the
 * tests can pin OK/NO_DATA/COLLECTING/DEGRADED, per-instance stats, step
 * promotion, caps, label filtering, deep links and the best-effort ledger write.
 */
class MetricsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final MetricDefinition RPS = new MetricDefinition(
            "http.rps", "RPS", "req/s",
            "sum by (instance) (rate(http_server_requests_seconds_count[${range}]))", true);
    private static final MetricDefinition FAILURE = new MetricDefinition(
            "http.failure.ratio", "Failure", "ratio",
            "sum by (instance) (rate(http_server_requests_errors_total[${range}]))", true);

    private MetricsProperties properties;
    private RunLedgerRepository repository;

    @BeforeEach
    void setUp() {
        properties = new MetricsProperties();
        properties.setGrafanaUrl("http://grafana.lab:3000/");
        properties.setTempoUrl("http://tempo.lab:3200/");
        properties.validate();
        repository = mock(RunLedgerRepository.class);
    }

    @Test
    void groupsByInstanceAndComputesTheStatsSummary() {
        AtomicReference<String> lastQuery = new AtomicReference<>();
        PrometheusGateway gateway = (query, start, end, step) -> {
            lastQuery.set(query);
            return matrix(series("app-1", 1.0, 3.0), series("app-2", 2.0, 4.0));
        };

        MetricsQueryViewResult view = query(gateway, null, null, null, RPS);

        assertThat(view.view().quality()).isEqualTo("OK");
        assertThat(view.view().metrics()).hasSize(1);
        MetricsViews.MetricView metric = view.view().metrics().get(0);
        assertThat(metric.quality()).isEqualTo("OK");
        assertThat(metric.series()).hasSize(2);
        assertThat(metric.series().get(0).labels()).containsEntry("instance", "app-1");
        assertThat(metric.series().get(0).points()).hasSize(2);
        assertThat(metric.series().get(0).stats().last()).isEqualTo(3.0);
        assertThat(metric.series().get(0).stats().avg()).isEqualTo(2.0);
        assertThat(metric.series().get(0).stats().min()).isEqualTo(1.0);
        assertThat(metric.series().get(0).stats().max()).isEqualTo(3.0);
        assertThat(metric.series().get(0).stats().samples()).isEqualTo(2);
        assertThat(lastQuery.get()).contains("[15s]");
        assertThat(view.view().window().stepSeconds()).isEqualTo(15);
    }

    @Test
    void anOldEmptyWindowIsNoDataNotNull() {
        MetricsQueryViewResult view = query(gatewayReturning(matrix()), null, null, null, RPS);

        assertThat(view.view().quality()).isEqualTo("NO_DATA");
        assertThat(view.view().metrics().get(0).quality()).isEqualTo("NO_DATA");
        assertThat(view.view().metrics().get(0).series()).isEmpty();
        assertThat(view.view().reason()).contains("no series");
    }

    @Test
    void aWindowReachingIntoTheScrapeGraceIsCollecting() {
        MetricsQueryViewResult view = query(gatewayReturning(matrix()), null, null, null,
                new MetricDefinition[]{RPS},
                NOW.minusSeconds(600).toString(), NOW.minusSeconds(5).toString());

        assertThat(view.view().quality()).isEqualTo("COLLECTING");
        assertThat(view.view().metrics().get(0).quality()).isEqualTo("COLLECTING");
        assertThat(view.view().metrics().get(0).series()).isEmpty();
        assertThat(view.view().metrics().get(0).reason())
                .contains("scrape interval")
                .contains("first usable sample time is unknown")
                .contains("scrape grace 15s");
    }

    @Test
    void aPrometheusFailureIsDegradedAndNeverZeroFilled() {
        PrometheusGateway gateway = (query, start, end, step) -> {
            throw new PrometheusQueryException("prometheus unreachable at http://prometheus:9090: refused");
        };

        MetricsQueryViewResult view = query(gateway, null, null, null, RPS);

        assertThat(view.view().quality()).isEqualTo("DEGRADED");
        assertThat(view.view().metrics().get(0).quality()).isEqualTo("DEGRADED");
        assertThat(view.view().metrics().get(0).series()).isEmpty();
        assertThat(view.view().reason()).contains("http.rps").contains("unreachable");
    }

    @Test
    void aPartialFailureKeepsEachMetricsOwnQuality() {
        PrometheusGateway gateway = (query, start, end, step) -> {
            if (query.contains("http_server_requests_seconds_count")) {
                return matrix(series("app-1", 5.0));
            }
            throw new PrometheusQueryException("prometheus returned HTTP 500: boom");
        };

        MetricsQueryViewResult view = query(gateway, null, null, null, RPS, FAILURE);

        assertThat(view.view().quality()).isEqualTo("DEGRADED");
        assertThat(view.view().metrics().get(0).quality()).isEqualTo("OK");
        assertThat(view.view().metrics().get(0).series()).hasSize(1);
        assertThat(view.view().metrics().get(1).quality()).isEqualTo("DEGRADED");
        assertThat(view.view().metrics().get(1).series()).isEmpty();
        assertThat(view.view().reason()).contains("http.failure.ratio").contains("HTTP 500");
    }

    @Test
    void maxPointsRaisesTheStepAndTheRangePlaceholder() {
        properties.setMaxPoints(10);
        AtomicLong lastStep = new AtomicLong();
        AtomicReference<String> lastQuery = new AtomicReference<>();
        PrometheusGateway gateway = (query, start, end, step) -> {
            lastStep.set(step);
            lastQuery.set(query);
            return matrix();
        };

        MetricsQueryViewResult view = query(gateway, null, null, null, new MetricDefinition[]{RPS},
                NOW.minusSeconds(360).toString(), NOW.minusSeconds(60).toString());

        assertThat(lastStep.get()).isEqualTo(30);
        assertThat(lastQuery.get()).contains("[30s]");
        assertThat(view.view().window().stepSeconds()).isEqualTo(30);
        assertThat(view.view().window().range()).isEqualTo("30s");
    }

    @Test
    void theSeriesCapTruncatesAndMarksThatMetricDegraded() {
        properties.setMaxSeries(3);
        PrometheusGateway gateway = (query, start, end, step) -> matrix(
                series("app-1", 1.0), series("app-2", 1.0), series("app-3", 1.0));

        MetricsQueryViewResult view = query(gateway, null, null, null, RPS, FAILURE);

        assertThat(view.view().quality()).isEqualTo("DEGRADED");
        assertThat(view.view().metrics().get(0).series()).hasSize(3);
        assertThat(view.view().metrics().get(0).quality()).isEqualTo("OK");
        assertThat(view.view().metrics().get(1).series()).isEmpty();
        assertThat(view.view().metrics().get(1).quality()).isEqualTo("DEGRADED");
        assertThat(view.view().metrics().get(1).reason()).contains("max-series=3");
    }

    @Test
    void thePointCapKeepsTheNewestTail() {
        properties.setMaxPoints(10);
        properties.setMinStepSeconds(1);
        PrometheusGateway gateway = (query, start, end, step) -> {
            double[] values = new double[20];
            for (int i = 0; i < values.length; i++) {
                values[i] = i;
            }
            return matrix(series("app-1", values));
        };

        MetricsQueryViewResult view = query(gateway, null, null, null, RPS);

        MetricsViews.SeriesView series = view.view().metrics().get(0).series().get(0);
        assertThat(series.points()).hasSize(10);
        assertThat(series.points().get(0)[1]).isEqualTo(10.0);
        assertThat(series.points().get(9)[1]).isEqualTo(19.0);
        assertThat(view.view().metrics().get(0).quality()).isEqualTo("DEGRADED");
        assertThat(view.view().metrics().get(0).reason()).contains("max-points=10");
    }

    @Test
    void highCardinalityLabelsNeverReachTheResponse() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("__name__", "http_server_requests_seconds_count");
        raw.put("instance", "app-1:8080");
        raw.put("status", "200");
        raw.put("url", "http://example/api/orders?token=secret");
        raw.put("trace_id", "abc123");
        raw.put("order_id", "42");
        raw.put("empty", "");
        PrometheusGateway gateway = (query, start, end, step) ->
                matrix(new PrometheusSeries(raw, List.of(new PrometheusPoint(1, 1))));

        MetricsQueryViewResult view = query(gateway, null, null, null, RPS);

        assertThat(view.view().metrics().get(0).series().get(0).labels())
                .containsOnlyKeys("instance", "status");
    }

    @Test
    void deepLinksUseTheExternalizedBasesAndFallBackToExplore() {
        MetricsQueryViewResult view = query(gatewayReturning(matrix()), null, null, null, RPS);
        Instant from = NOW.minusSeconds(300);
        Instant to = NOW.minusSeconds(60);

        assertThat(view.view().links().grafanaUrl()).isEqualTo(
                "http://grafana.lab:3000/explore?from=" + from.toEpochMilli() + "&to=" + to.toEpochMilli());
        assertThat(view.view().links().tempoUrl()).isEqualTo(
                "http://tempo.lab:3200/api/search?start=" + from.getEpochSecond() + "&end=" + to.getEpochSecond());

        properties.setGrafanaDashboardUid("lab-overview");
        MetricsQueryViewResult withDashboard = query(gatewayReturning(matrix()), null, null, null, RPS);
        assertThat(withDashboard.view().links().grafanaUrl()).isEqualTo(
                "http://grafana.lab:3000/d/lab-overview?from=" + from.toEpochMilli()
                        + "&to=" + to.toEpochMilli());
    }

    @Test
    void runWindowIsStartMinusScrapeGraceToEndPlusDrainGrace() {
        Instant started = NOW.minusSeconds(120);
        Instant ended = NOW.minusSeconds(60);
        when(repository.findRun("run-1")).thenReturn(Optional.of(run(started, ended)));
        AtomicLong seenStart = new AtomicLong();
        AtomicLong seenEnd = new AtomicLong();
        PrometheusGateway gateway = (query, start, end, step) -> {
            seenStart.set(start);
            seenEnd.set(end);
            return matrix(series("app-1", 1.0));
        };

        MetricsService service = service(gateway, RPS);
        MetricsViews.MetricsQueryView view = service.queryRun("run-1", null, null, null, null);

        assertThat(view.source()).isEqualTo("run");
        assertThat(view.runId()).isEqualTo("run-1");
        assertThat(view.phase()).isEqualTo("adhoc");
        assertThat(seenStart.get()).isEqualTo(started.getEpochSecond() - properties.getScrapeGraceSeconds());
        assertThat(seenEnd.get()).isEqualTo(ended.getEpochSecond() + properties.getDrainGraceSeconds());
        verify(repository).insertMetricWindow(eq("run-1"), eq("adhoc"), eq("http.rps"),
                contains("[15s]"), eq(started.minusSeconds(15)), eq(ended.plusSeconds(30)),
                anyString(), eq("OK"));
    }

    @Test
    void explicitFromToOverrideTheRunWindowExactly() {
        when(repository.findRun("run-1")).thenReturn(Optional.of(run(NOW.minusSeconds(120), null)));
        AtomicLong seenStart = new AtomicLong();
        AtomicLong seenEnd = new AtomicLong();
        PrometheusGateway gateway = (query, start, end, step) -> {
            seenStart.set(start);
            seenEnd.set(end);
            return matrix();
        };
        Instant from = NOW.minusSeconds(600);
        Instant to = NOW.minusSeconds(500);

        MetricsViews.MetricsQueryView view = service(gateway, RPS)
                .queryRun("run-1", from.toString(), to.toString(), "http.rps", "after");

        assertThat(seenStart.get()).isEqualTo(from.getEpochSecond());
        assertThat(seenEnd.get()).isEqualTo(to.getEpochSecond());
        assertThat(view.phase()).isEqualTo("after");
    }

    @Test
    void oneSidedFromToIsRejected() {
        when(repository.findRun("run-1")).thenReturn(Optional.of(run(NOW.minusSeconds(120), null)));

        assertThatThrownBy(() -> service(gatewayReturning(matrix()), RPS)
                .queryRun("run-1", NOW.minusSeconds(60).toString(), null, null, null))
                .isInstanceOf(InvalidMetricsRequestException.class)
                .hasMessageContaining("Provide both 'from' and 'to'");
    }

    @Test
    void anUnknownRunIs404() {
        when(repository.findRun("run-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(gatewayReturning(matrix()), RPS)
                .queryRun("run-x", null, null, null, null))
                .isInstanceOf(LedgerRunNotFoundException.class)
                .hasMessageContaining("run-x");
    }

    @Test
    void aLedgerOutageDuringTheRunLookupIs503NotAGenericFailure() {
        when(repository.findRun("run-1"))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatThrownBy(() -> service(gatewayReturning(matrix()), RPS)
                .queryRun("run-1", null, null, null, null))
                .isInstanceOf(com.javalearn.lab.chaos.error.LedgerUnavailableException.class)
                .hasMessageContaining("run-1")
                .hasMessageContaining("connection refused");
    }

    @Test
    void aLedgerWriteFailureDoesNotFailTheQuery() {
        when(repository.findRun("run-1")).thenReturn(Optional.of(run(NOW.minusSeconds(120), null)));
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(repository).insertMetricWindow(anyString(), anyString(), anyString(),
                        anyString(), any(), any(), anyString(), anyString());

        MetricsViews.MetricsQueryView view = service(gatewayReturning(matrix(series("app-1", 1.0))), RPS)
                .queryRun("run-1", null, null, null, null);

        assertThat(view.quality()).isEqualTo("OK");
        assertThat(view.warnings()).anyMatch(warning -> warning.contains("ledger write failed"));
    }

    @Test
    void unknownKeysAndTooManyKeysAreRejected() {
        MetricsService service = service(gatewayReturning(matrix()), RPS);

        assertThatThrownBy(() -> service.queryWindow(NOW.minusSeconds(300).toString(),
                NOW.minusSeconds(60).toString(), "nope", null))
                .isInstanceOf(InvalidMetricsRequestException.class)
                .hasMessageContaining("Unknown metric key(s): nope")
                .hasMessageContaining("http.rps");

        properties.setMaxKeys(2);
        MetricsService capped = service(gatewayReturning(matrix()), RPS);
        assertThatThrownBy(() -> capped.queryWindow(NOW.minusSeconds(300).toString(),
                NOW.minusSeconds(60).toString(), "http.rps,http.rps,http.rps", null))
                .isInstanceOf(InvalidMetricsRequestException.class)
                .hasMessageContaining("At most 2 metric keys");
    }

    @Test
    void anInvalidPhaseIsRejected() {
        assertThatThrownBy(() -> service(gatewayReturning(matrix()), RPS)
                .queryWindow(NOW.minusSeconds(300).toString(), NOW.minusSeconds(60).toString(),
                        null, "bad phase"))
                .isInstanceOf(InvalidMetricsRequestException.class)
                .hasMessageContaining("'phase'");
    }

    @Test
    void promqlWithoutTheRangePlaceholderIsSentVerbatim() {
        AtomicReference<String> lastQuery = new AtomicReference<>();
        PrometheusGateway gateway = (query, start, end, step) -> {
            lastQuery.set(query);
            return matrix();
        };
        MetricDefinition instant = new MetricDefinition("jvm.live", "Live", "threads",
                "max by (instance) (jvm_threads_live_threads{job=\"seckill-app\"})", true);

        service(gateway, instant).queryWindow(NOW.minusSeconds(300).toString(),
                NOW.minusSeconds(60).toString(), null, null);

        assertThat(lastQuery.get()).isEqualTo(
                "max by (instance) (jvm_threads_live_threads{job=\"seckill-app\"})");
    }

    @Test
    void aSeriesWithoutFiniteSamplesIsNotData() {
        PrometheusGateway gateway = (query, start, end, step) -> matrix(
                new PrometheusSeries(Map.of("instance", "app-1"),
                        List.of(new PrometheusPoint(1, Double.NaN))));

        MetricsQueryViewResult view = query(gateway, null, null, null, RPS);

        assertThat(view.view().metrics().get(0).series()).isEmpty();
        assertThat(view.view().quality()).isEqualTo("NO_DATA");
    }

    @Test
    void aMixOfOkAndNoDataStaysOkButCollectingWinsOverOk() {
        PrometheusGateway ok = (query, start, end, step) ->
                query.contains("errors_total") ? matrix() : matrix(series("app-1", 1.0));

        MetricsQueryViewResult mixed = query(ok, null, null, null, RPS, FAILURE);
        assertThat(mixed.view().metrics().get(0).quality()).isEqualTo("OK");
        assertThat(mixed.view().metrics().get(1).quality()).isEqualTo("NO_DATA");
        assertThat(mixed.view().quality()).isEqualTo("OK");

        MetricsQueryViewResult collecting = query(ok, null, null, null,
                new MetricDefinition[]{RPS, FAILURE},
                NOW.minusSeconds(600).toString(), NOW.minusSeconds(5).toString());
        assertThat(collecting.view().metrics().get(0).quality()).isEqualTo("OK");
        assertThat(collecting.view().metrics().get(1).quality()).isEqualTo("COLLECTING");
        assertThat(collecting.view().quality()).isEqualTo("COLLECTING");
    }

    // ------------------------------------------------------------- helpers

    private MetricsQueryViewResult query(PrometheusGateway gateway, String ignoredKeys,
                                         String ignoredPhase, String ignoredRun,
                                         MetricDefinition... definitions) {
        return query(gateway, ignoredKeys, ignoredPhase, ignoredRun, definitions,
                NOW.minusSeconds(300).toString(), NOW.minusSeconds(60).toString());
    }

    private MetricsQueryViewResult query(PrometheusGateway gateway, String ignoredKeys,
                                         String ignoredPhase, String ignoredRun,
                                         MetricDefinition[] definitions, String from, String to) {
        MetricsViews.MetricsQueryView view = service(gateway, definitions)
                .queryWindow(from, to, ignoredKeys, ignoredPhase);
        return new MetricsQueryViewResult(view);
    }

    private MetricsService service(PrometheusGateway gateway, MetricDefinition... definitions) {
        MetricCatalog catalog = new MetricCatalog(1, List.of(definitions), properties.getMaxKeys());
        return new MetricsService(properties, catalog, gateway, repository, MAPPER, CLOCK);
    }

    private static PrometheusGateway gatewayReturning(PrometheusMatrix matrix) {
        return (query, start, end, step) -> matrix;
    }

    private static PrometheusMatrix matrix(PrometheusSeries... series) {
        return new PrometheusMatrix(List.of(series));
    }

    private static PrometheusSeries series(String instance, double... values) {
        List<PrometheusPoint> points = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            points.add(new PrometheusPoint(1_000 + i * 15, values[i]));
        }
        return new PrometheusSeries(Map.of("instance", instance), points);
    }

    private static RunRecord run(Instant startedAt, Instant endedAt) {
        return new RunRecord("run-1", "spike", RunStatus.RUNNING, "commit", "local",
                NOW.minusSeconds(600), NOW.minusSeconds(600), startedAt, endedAt);
    }

    private record MetricsQueryViewResult(MetricsViews.MetricsQueryView view) {
    }
}
