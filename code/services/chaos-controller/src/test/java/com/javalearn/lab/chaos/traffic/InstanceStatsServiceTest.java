package com.javalearn.lab.chaos.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.instance.InstanceConfigRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import com.javalearn.lab.chaos.metrics.MetricsService;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.metrics.PrometheusMatrix;
import com.javalearn.lab.chaos.metrics.PrometheusPoint;
import com.javalearn.lab.chaos.metrics.PrometheusSeries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceStatsServiceTest {

    private static final String RUN_ID = "run-1";
    private static final Instant FROM = Instant.parse("2026-10-29T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-29T10:05:00Z");

    private MetricsService metricsService;
    private InstanceConfigRepository configRepository;
    private RunRecord run;
    private TrafficSplitProperties properties;

    @BeforeEach
    void setUp() {
        metricsService = mock(MetricsService.class);
        configRepository = mock(InstanceConfigRepository.class);
        properties = new TrafficSplitProperties();
        run = new RunRecord(RUN_ID, "load", "load run", "app-1,app-2,app-3",
                RunStatus.COMPLETED, "abc", "tester",
                FROM.minusSeconds(60), TO, FROM, TO, false);
        when(metricsService.requireRun(RUN_ID)).thenReturn(run);
        when(metricsService.runWindow(run)).thenReturn(new MetricsService.RunWindow(FROM, TO));
        when(configRepository.list()).thenReturn(List.of());
    }

    private InstanceStatsService service(PrometheusGateway gateway, Path conf) {
        properties.setNginxUpstreamConf(conf.toString());
        return new InstanceStatsService(metricsService, gateway, properties,
                configRepository, new ObjectMapper());
    }

    private static PrometheusMatrix matrix(double value, String... instances) {
        List<PrometheusSeries> series = new ArrayList<>();
        for (String instance : instances) {
            series.add(new PrometheusSeries(Map.of("instance", instance),
                    List.of(new PrometheusPoint(TO.getEpochSecond(), value))));
        }
        return new PrometheusMatrix(series);
    }

    @Test
    void computesHitsShareLatencyAndRejectionsForEveryNode(@TempDir Path dir) throws Exception {
        Path conf = dir.resolve("default.conf");
        Files.writeString(conf, """
                upstream seckill_backend {
                    server app-1:8080;
                    server app-2:8080;
                    server app-3:8080;
                }
                """, StandardCharsets.UTF_8);
        List<String> queries = new ArrayList<>();
        PrometheusGateway gateway = (query, start, end, step) -> {
            queries.add(query);
            if (query.contains("histogram_quantile(0.95")) return matrix(0.1, "app-1:8080", "app-2:8080");
            if (query.contains("histogram_quantile(0.99")) return matrix(0.2, "app-1:8080", "app-2:8080");
            if (query.contains("status=~\"2..\"")) return matrix(100, "app-1:8080", "app-2:8080");
            if (query.contains("status=~\"5..\"")) return matrix(20, "app-1:8080", "app-2:8080");
            if (query.contains("executor_rejected_total")) return matrix(3, "app-2:8080");
            if (query.contains("seconds_sum")) return matrix(0.05, "app-1:8080", "app-2:8080");
            if (query.contains("rate(http_server_requests_seconds_count")) return matrix(2.0, "app-1:8080", "app-2:8080");
            if (query.contains("increase(http_server_requests_seconds_count")) {
                return matrix(100, "app-1:8080", "app-2:8080");
            }
            return new PrometheusMatrix(List.of());
        };

        InstanceStatsViews.InstanceStatsView view = service(gateway, conf).query(RUN_ID);

        assertThat(view.quality()).isEqualTo("OK");
        assertThat(view.note()).contains("/actuator").contains("Nginx");
        assertThat(view.instances()).hasSize(3);
        InstanceStatsViews.InstanceRow app1 = view.instances().get(0);
        assertThat(app1.instance()).isEqualTo("app-1");
        assertThat(app1.hits()).isEqualTo(100.0);
        assertThat(app1.sharePercent()).isEqualTo(50.0);
        assertThat(app1.success()).isEqualTo(100.0);
        assertThat(app1.failed()).isEqualTo(20.0);
        assertThat(app1.avgMs()).isEqualTo(50.0);
        assertThat(app1.p95Ms()).isEqualTo(100.0);
        assertThat(app1.errorRate()).isEqualTo(0.167);
        assertThat(app1.rejected()).isNull();
        assertThat(app1.weight()).isEqualTo(1);
        assertThat(app1.expectedSharePercent()).isEqualTo(33.333);
        InstanceStatsViews.InstanceRow app3 = view.instances().get(2);
        assertThat(app3.instance()).isEqualTo("app-3");
        assertThat(app3.hits()).isNull();
        assertThat(app3.sharePercent()).isNull();
        assertThat(view.upstream().available()).isTrue();
        assertThat(queries).anyMatch(query -> query.contains("uri!~\"/actuator.*\""));
        assertThat(view.warnings()).anyMatch(warning -> warning.startsWith("app-1 实测占比"));
    }

    @Test
    void noSeriesIsNoDataWithNullValuesNotZero(@TempDir Path dir) {
        properties.setNginxUpstreamConf(dir.resolve("missing.conf").toString());
        PrometheusGateway gateway = (query, start, end, step) -> new PrometheusMatrix(List.of());

        InstanceStatsViews.InstanceStatsView view = service(gateway, dir.resolve("missing.conf")).query(RUN_ID);

        assertThat(view.quality()).isEqualTo("NO_DATA");
        assertThat(view.instances()).hasSize(3);
        assertThat(view.instances()).allSatisfy(row -> {
            assertThat(row.hits()).isNull();
            assertThat(row.sharePercent()).isNull();
            assertThat(row.avgMs()).isNull();
        });
        assertThat(view.upstream().available()).isFalse();
        assertThat(view.upstream().reason()).isNotBlank();
        assertThat(view.note()).contains("权重不可用");
    }

    @Test
    void aPrometheusFailureDegradesButKeepsTheRows(@TempDir Path dir) {
        PrometheusGateway gateway = (query, start, end, step) -> {
            if (query.contains("histogram_quantile(0.95")) {
                throw new IllegalStateException("prometheus timeout");
            }
            if (query.contains("increase(http_server_requests_seconds_count")
                    && !query.contains("status")) {
                return matrix(7, "app-1:8080");
            }
            return new PrometheusMatrix(List.of());
        };

        InstanceStatsViews.InstanceStatsView view = service(gateway, dir.resolve("x.conf")).query(RUN_ID);

        assertThat(view.quality()).isEqualTo("DEGRADED");
        assertThat(view.warnings()).anyMatch(warning -> warning.contains("P95 查询失败"));
        assertThat(view.instances().get(0).hits()).isEqualTo(7.0);
        assertThat(view.instances().get(0).p95Ms()).isNull();
    }
}
