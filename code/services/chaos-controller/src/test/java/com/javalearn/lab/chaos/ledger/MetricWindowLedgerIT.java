package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.metrics.MetricCatalog;
import com.javalearn.lab.chaos.metrics.MetricDefinition;
import com.javalearn.lab.chaos.metrics.MetricsProperties;
import com.javalearn.lab.chaos.metrics.MetricsService;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.metrics.PrometheusMatrix;
import com.javalearn.lab.chaos.metrics.PrometheusPoint;
import com.javalearn.lab.chaos.metrics.PrometheusQueryException;
import com.javalearn.lab.chaos.metrics.PrometheusSeries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.javalearn.lab.chaos.metrics.MetricsViews.MetricsQueryView;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real MySQL (Testcontainers, same 8.4.4 image as the ledger ITs): B1-03 writes
 * one {@code lab_metric_window} row per metric per query, supports repeated
 * queries on the same run (a new batch each time) and keeps the HTTP answer
 * intact when the ledger write fails.
 */
class MetricWindowLedgerIT {

    private static final Instant NOW = LedgerTestDatabase.NOW;
    private static final MetricDefinition RPS = new MetricDefinition(
            "http.rps", "RPS", "req/s",
            "sum by (instance) (rate(http_server_requests_seconds_count[${range}]))", true);

    private RunLedgerRepository repository;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.clean();
        repository = LedgerTestDatabase.repository();
    }

    @Test
    void aRunQueryWritesOneWindowRowPerMetricWithItsQualityAndStats() throws Exception {
        Instant started = NOW.minusSeconds(120);
        Instant ended = NOW.minusSeconds(60);
        insertRun(started, ended);

        MetricsQueryView view = service(repository, gatewayReturning(matrix(series("app-1", 1.0, 3.0))))
                .queryRun("run-metrics-1", null, null, null, null);

        assertThat(view.quality()).isEqualTo("OK");
        List<Map<String, Object>> rows = rows("run-metrics-1");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("phase")).isEqualTo("adhoc");
        assertThat(row.get("metric_key")).isEqualTo("http.rps");
        assertThat((String) row.get("promql")).contains("[15s]");
        assertThat(toInstant(row.get("window_start")))
                .isEqualTo(started.minusSeconds(15));
        assertThat(toInstant(row.get("window_end")))
                .isEqualTo(ended.plusSeconds(30));
        assertThat(row.get("data_quality")).isEqualTo("OK");
        JsonNode stats = new ObjectMapper().readTree((String) row.get("stats"));
        assertThat(stats.path("seriesCount").asInt()).isEqualTo(1);
        assertThat(stats.path("samples").asInt()).isEqualTo(2);
        assertThat(stats.path("series").get(0).path("labels").path("instance").asText())
                .isEqualTo("app-1");
        assertThat(stats.path("series").get(0).path("stats").path("last").asDouble()).isEqualTo(3.0);
    }

    @Test
    void repeatedQueriesOnTheSameRunProduceTwoBatches() {
        insertRun(NOW.minusSeconds(120), NOW.minusSeconds(60));
        MetricsService service = service(repository,
                gatewayReturning(matrix(series("app-1", 1.0))));

        service.queryRun("run-metrics-1", null, null, null, null);
        service.queryRun("run-metrics-1", null, null, null, null);

        assertThat(rows("run-metrics-1")).hasSize(2);
    }

    @Test
    void aDegradedQueryStillWritesItsQualityAndReason() throws Exception {
        insertRun(NOW.minusSeconds(120), NOW.minusSeconds(60));
        PrometheusGateway failing = (query, start, end, step) -> {
            throw new PrometheusQueryException("prometheus unreachable at http://prometheus:9090");
        };

        MetricsQueryView view = service(repository, failing)
                .queryRun("run-metrics-1", null, null, null, "during");

        assertThat(view.quality()).isEqualTo("DEGRADED");
        List<Map<String, Object>> rows = rows("run-metrics-1");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("phase")).isEqualTo("during");
        assertThat(rows.get(0).get("data_quality")).isEqualTo("DEGRADED");
        JsonNode stats = new ObjectMapper().readTree((String) rows.get(0).get("stats"));
        assertThat(stats.path("seriesCount").asInt()).isZero();
        assertThat(stats.path("reason").asText()).contains("unreachable");
    }

    @Test
    void aLedgerWriteFailureLeavesTheQueryAnswerIntact() {
        insertRun(NOW.minusSeconds(120), NOW.minusSeconds(60));
        RunLedgerRepository failing = new FailingRepository();

        MetricsQueryView view = service(failing, gatewayReturning(matrix(series("app-1", 1.0))))
                .queryRun("run-metrics-1", null, null, null, null);

        assertThat(view.quality()).isEqualTo("OK");
        assertThat(view.metrics().get(0).series()).hasSize(1);
        assertThat(view.warnings()).isNotEmpty();
        assertThat(rows("run-metrics-1")).isEmpty();
    }

    private void insertRun(Instant started, Instant ended) {
        repository.insertRun(new RunRecord("run-metrics-1", "spike", RunStatus.COMPLETED,
                "test-commit", "local", NOW.minusSeconds(600), NOW.minusSeconds(600),
                started, ended));
    }

    private List<Map<String, Object>> rows(String runId) {
        return LedgerTestDatabase.jdbc().queryForList(
                "SELECT phase, metric_key, promql, window_start, window_end, stats, data_quality "
                        + "FROM lab_metric_window WHERE run_id = ? ORDER BY id", runId);
    }

    /** MySQL may hand back Timestamp or LocalDateTime depending on the driver path. */
    private static Instant toInstant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private static MetricsService service(RunLedgerRepository repository, PrometheusGateway gateway) {
        MetricsProperties properties = new MetricsProperties();
        properties.validate();
        MetricCatalog catalog = new MetricCatalog(1, List.of(RPS), properties.getMaxKeys());
        return new MetricsService(properties, catalog, gateway, repository, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PrometheusGateway gatewayReturning(PrometheusMatrix matrix) {
        return (query, start, end, step) -> matrix;
    }

    private static PrometheusMatrix matrix(PrometheusSeries... series) {
        return new PrometheusMatrix(List.of(series));
    }

    private static PrometheusSeries series(String instance, double... values) {
        List<PrometheusPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            points.add(new PrometheusPoint(1_000 + i * 15, values[i]));
        }
        return new PrometheusSeries(Map.of("instance", instance), points);
    }

    private static final class FailingRepository extends RunLedgerRepository {

        private FailingRepository() {
            super(LedgerTestDatabase.jdbc());
        }

        @Override
        public void insertMetricWindow(String runId, String phase, String metricKey, String promql,
                                       Instant windowStart, Instant windowEnd, String statsJson,
                                       String dataQuality) {
            throw new DataAccessResourceFailureException("ledger unavailable");
        }
    }
}
