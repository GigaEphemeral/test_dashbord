package com.javalearn.lab.chaos.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.gateway.ContainerFacts;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.instance.InstanceConfigProperties;
import com.javalearn.lab.chaos.instance.InstanceConfigRepository;
import com.javalearn.lab.chaos.instance.StoredInstanceConfig;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.metrics.PrometheusMatrix;
import com.javalearn.lab.chaos.metrics.PrometheusPoint;
import com.javalearn.lab.chaos.metrics.PrometheusQueryException;
import com.javalearn.lab.chaos.metrics.PrometheusSeries;
import com.javalearn.lab.chaos.model.ContainerView;
import com.javalearn.lab.chaos.precheck.PrecheckProperties;
import com.javalearn.lab.chaos.topology.TopologyViews.TopologyMetricsView;
import com.javalearn.lab.chaos.topology.TopologyViews.TopologyNodeView;
import com.javalearn.lab.chaos.topology.TopologyViews.TopologyView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B5-01a contract tests with a fake Docker gateway, fake Prometheus and fake
 * ledger repositories: full topology, missing containers, enabled selection,
 * hidden sidecars, UNWIRED tiers, null-not-zero and the JDK 8 limitation.
 */
class TopologyServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TopologyProperties properties;
    private PrecheckProperties precheck;
    private InstanceConfigProperties instance;
    private ContainerGateway containers;
    private PrometheusGateway prometheus;
    private InstanceConfigRepository configs;
    private RunLedgerRepository runs;

    @BeforeEach
    void setUp() {
        properties = new TopologyProperties();
        properties.setCacheSeconds(0);
        properties.validate();
        precheck = new PrecheckProperties();
        instance = new InstanceConfigProperties();
        containers = mock(ContainerGateway.class);
        prometheus = mock(PrometheusGateway.class);
        configs = mock(InstanceConfigRepository.class);
        runs = mock(RunLedgerRepository.class);

        when(containers.listLabContainers()).thenReturn(List.of(
                container("jl-nginx", "running", "healthy"),
                container("jl-app-1", "running", "healthy"),
                container("jl-app-2", "running", "healthy"),
                container("jl-redis-1", "running", "healthy"),
                container("jl-redis-2", "running", "healthy"),
                container("jl-mysql-1", "exited", "none"),
                container("jl-rabbitmq", "running", "healthy"),
                container("jl-nginx-exporter", "running", "none")));
        when(containers.inspectLabContainerFacts("jl-app-1")).thenReturn(Optional.of(
                new ContainerFacts("jl-app-1", "java-learn-lab/seckill-app:jdk21",
                        "sha256:app1", Map.of())));
        when(containers.inspectLabContainerFacts("jl-app-2")).thenReturn(Optional.of(
                new ContainerFacts("jl-app-2", "java-learn-lab/seckill-app:jdk8",
                        "sha256:app2", Map.of())));
        when(containers.inspectLabContainerFacts("jl-app-3")).thenReturn(Optional.empty());
        when(configs.list()).thenReturn(List.of(
                stored("app-1", 21), stored("app-2", 8)));
        when(runs.listRuns(any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of(run("app-1,app-2")));
        when(prometheus.queryInstant(anyString(), anyLong()))
                .thenAnswer(invocation -> metric(invocation.getArgument(0)));
    }

    @Test
    void keepsTheFullTopologyAndUsesNullForMissingData() {
        TopologyView view = service().snapshot(null, false);

        assertThat(view.nodes()).hasSize(23);
        assertThat(view.enabledNodes()).containsExactly("app-1", "app-2");
        assertThat(view.includeSidecars()).isFalse();

        TopologyNodeView app1 = node(view, "jl-app-1");
        assertThat(app1.health()).isEqualTo("UP");
        assertThat(app1.enabled()).isTrue();
        assertThat(app1.observability()).isEqualTo("FULL-OBSERVABILITY");
        assertThat(app1.jdkVersion()).isEqualTo("21");
        assertThat(app1.imageTag()).isEqualTo("jdk21");
        assertThat(app1.imageId()).isEqualTo("sha256:app1");
        assertThat(app1.dataQuality()).isEqualTo("OK");
        TopologyMetricsView metrics = app1.metrics();
        assertThat(metrics.cpuPercent()).isEqualTo(5.0);
        assertThat(metrics.heapUsedBytes()).isEqualTo(1_000_000.0);
        assertThat(metrics.heapLimitBytes()).isEqualTo(536_870_912.0);
        assertThat(metrics.threads()).isEqualTo(42.0);
        assertThat(metrics.activeThreads()).isEqualTo(3.0);
        assertThat(metrics.poolCore()).isEqualTo(4.0);
        assertThat(metrics.poolMax()).isEqualTo(8.0);
        assertThat(metrics.poolQueue()).isEqualTo(2.0);
        assertThat(metrics.poolRejected()).isEqualTo(7.0);
        assertThat(metrics.requestsPerSecond()).isEqualTo(2.5);
        assertThat(metrics.avgResponseMs()).isEqualTo(200.0);
        assertThat(metrics.p95Ms()).isEqualTo(150.0);
        assertThat(metrics.updatedAtEpochSeconds()).isEqualTo(NOW.getEpochSecond());
        assertThat(metrics.memUsedBytes()).isNull();
        assertThat(metrics.memLimitBytes()).isNull();

        TopologyNodeView app2 = node(view, "jl-app-2");
        assertThat(app2.jdkVersion()).isEqualTo("8");
        assertThat(app2.observability()).isEqualTo("LIMITED-OBSERVABILITY");
        assertThat(app2.notes()).anyMatch(note -> note.contains("LIMITED-OBSERVABILITY"));

        TopologyNodeView app3 = node(view, "jl-app-3");
        assertThat(app3.enabled()).isFalse();
        assertThat(app3.dataQuality()).isEqualTo("NO_DATA");

        TopologyNodeView redis1 = node(view, "jl-redis-1");
        assertThat(redis1.dataQuality()).isEqualTo("OK");
        assertThat(redis1.metrics().memUsedBytes()).isEqualTo(2_776_808.0);
        assertThat(redis1.metrics().cpuPercent()).isNull();

        assertThat(view.nodes().stream().map(TopologyNodeView::id))
                .doesNotContain("jl-redis-3", "jl-sentinel-3");

        TopologyNodeView mysql1 = node(view, "jl-mysql-1");
        assertThat(mysql1.health()).isEqualTo("NOT_RUNNING");
        assertThat(mysql1.dataQuality()).isEqualTo("NO_DATA");

        TopologyNodeView router = node(view, "jl-mysql-router");
        assertThat(router.health()).isEqualTo("NOT_RUNNING");
        assertThat(router.dataQuality()).isEqualTo("UNWIRED");
        assertThat(router.notes()).anyMatch(note -> note.contains("MySQL Router"));
        assertThat(router.metrics().cpuPercent()).isNull();

        TopologyNodeView toxiproxy = node(view, "jl-toxiproxy");
        assertThat(toxiproxy.dataQuality()).isEqualTo("UNWIRED");
        assertThat(toxiproxy.metrics().requestsPerSecond()).isNull();

        TopologyNodeView nginx = node(view, "jl-nginx");
        assertThat(nginx.dataQuality()).isEqualTo("OK");
        assertThat(nginx.notes()).anyMatch(note -> note.contains("nginx_connections_active=6"));

        assertThat(view.edges()).hasSize(30);
        assertThat(view.edges()).anyMatch(edge -> edge.from().equals("jl-nginx")
                && edge.to().equals("jl-app-1") && edge.kind().equals("http"));
        assertThat(view.edges()).anyMatch(edge -> edge.from().equals("jl-app-1")
                && edge.to().equals("jl-mysql-router") && edge.kind().equals("mysql"));
    }

    @Test
    void hidesSidecarsUnlessTheToggleIsOn() {
        TopologyView hidden = service().snapshot(null, false);
        TopologyView shown = service().snapshot(null, true);

        assertThat(node(hidden, "jl-nginx-exporter").hidden()).isTrue();
        assertThat(node(hidden, "jl-redis-1-exporter").hidden()).isTrue();
        assertThat(node(shown, "jl-nginx-exporter").hidden()).isFalse();
        assertThat(node(shown, "jl-redis-1-exporter").hidden()).isFalse();
        assertThat(shown.includeSidecars()).isTrue();
        assertThat(node(shown, "jl-nginx-exporter").dataQuality()).isEqualTo("UNWIRED");
    }

    @Test
    void queryEnabledWinsOverTheNewestRun() {
        TopologyView view = service().snapshot("jl-app-1,redis-2", false);

        assertThat(view.enabledNodes()).containsExactly("app-1", "redis-2");
        assertThat(node(view, "jl-app-1").enabled()).isTrue();
        assertThat(node(view, "jl-app-2").enabled()).isFalse();
        assertThat(node(view, "jl-redis-2").enabled()).isTrue();
        assertThat(node(view, "jl-redis-1").enabled()).isFalse();
        assertThat(node(view, "jl-nginx").enabled()).isTrue();
    }

    @Test
    void fallsBackToTheNewestRunWithNodes() {
        when(runs.listRuns(any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of(run("app-1,app-3")));

        TopologyView view = service().snapshot(null, false);

        assertThat(view.enabledNodes()).containsExactly("app-1", "app-3");
        assertThat(node(view, "jl-app-3").enabled()).isTrue();
        assertThat(node(view, "jl-app-2").enabled()).isFalse();
    }

    @Test
    void fallsBackToAllConfiguredAppsWithoutARun() {
        when(runs.listRuns(any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of());

        TopologyView view = service().snapshot(null, false);

        assertThat(view.enabledNodes()).containsExactly("app-1", "app-2", "app-3");
        assertThat(node(view, "jl-app-3").enabled()).isTrue();
        assertThat(node(view, "jl-redis-1").enabled()).isFalse();
    }

    @Test
    void aPrometheusFailureIsNoDataAndNeverZeroFilled() {
        when(prometheus.queryInstant(anyString(), anyLong()))
                .thenThrow(new PrometheusQueryException(
                        "prometheus unreachable at http://prometheus:9090: refused"));

        TopologyView view = service().snapshot(null, false);

        TopologyNodeView app1 = node(view, "jl-app-1");
        assertThat(app1.dataQuality()).isEqualTo("NO_DATA");
        assertThat(app1.metrics().cpuPercent()).isNull();
        assertThat(app1.metrics().requestsPerSecond()).isNull();
        assertThat(app1.notes()).anyMatch(note -> note.contains("prometheus unreachable"));
    }

    @Test
    void dockerOutageKeepsEveryNodeAsUnknown() {
        when(containers.listLabContainers())
                .thenThrow(new com.javalearn.lab.chaos.error.DockerUnavailableException("down"));

        TopologyView view = service().snapshot(null, false);

        assertThat(view.nodes()).hasSize(23);
        assertThat(node(view, "jl-app-1").health()).isEqualTo("UNKNOWN");
        assertThat(node(view, "jl-app-1").dataQuality()).isEqualTo("NO_DATA");
        assertThat(node(view, "jl-app-1").notes())
                .anyMatch(note -> note.contains("Docker is unavailable"));
    }

    private TopologyService service() {
        return new TopologyService(properties, precheck, instance, containers, prometheus,
                configs, runs, MAPPER, CLOCK);
    }

    private static TopologyNodeView node(TopologyView view, String id) {
        return view.nodes().stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing node " + id));
    }

    private static ContainerView container(String name, String state, String health) {
        return new ContainerView(name, state, health);
    }

    private static StoredInstanceConfig stored(String instance, int jdk) {
        return new StoredInstanceConfig(instance, "{\"jdkVersion\":" + jdk + "}", null,
                com.javalearn.lab.chaos.instance.InstanceConfigStatus.APPLIED, 1, NOW, NOW,
                null, null, "jdk" + jdk, "sha256:" + instance, NOW, null, null);
    }

    private static RunRecord run(String nodes) {
        return new RunRecord("run-test", "load", null, nodes, RunStatus.COMPLETED, "sha", "local",
                NOW, NOW, NOW, NOW, false);
    }

    private static PrometheusMatrix metric(String query) {
        if (query.startsWith("process_cpu_usage")) {
            return vector(0.05);
        }
        if (query.contains("jvm_memory_used_bytes")) {
            return vector(1_000_000);
        }
        if (query.contains("jvm_memory_max_bytes")) {
            return vector(536_870_912);
        }
        if (query.startsWith("jvm_threads_live_threads")) {
            return vector(42);
        }
        if (query.startsWith("executor_active_threads")) {
            return vector(3);
        }
        if (query.startsWith("executor_pool_core_threads")) {
            return vector(4);
        }
        if (query.startsWith("executor_pool_size_threads")) {
            return vector(5);
        }
        if (query.startsWith("executor_pool_max_threads")) {
            return vector(8);
        }
        if (query.startsWith("executor_queued_tasks")) {
            return vector(2);
        }
        if (query.startsWith("executor_rejected_total")) {
            return vector(7);
        }
        if (query.contains("http_server_requests_seconds_sum")) {
            return vector(0.2);
        }
        if (query.contains("http_server_requests_seconds_bucket")) {
            return vector(0.15);
        }
        if (query.contains("http_server_requests_seconds_count")) {
            return vector(2.5);
        }
        if (query.startsWith("redis_up")) {
            return vector(1);
        }
        if (query.startsWith("redis_memory_used_bytes")) {
            return vector(2_776_808);
        }
        if (query.startsWith("mysql_up")) {
            return vector(1);
        }
        if (query.startsWith("nginx_connections_active")) {
            return vector(6);
        }
        if (query.contains("rabbitmq_queue_messages_ready")) {
            return vector(9);
        }
        return new PrometheusMatrix(List.of());
    }

    private static PrometheusMatrix vector(double value) {
        return new PrometheusMatrix(List.of(new PrometheusSeries(Map.of(),
                List.of(new PrometheusPoint(NOW.getEpochSecond(), value)))));
    }
}
