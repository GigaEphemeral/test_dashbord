package com.javalearn.lab.chaos.precheck;

import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.ChaosActionView;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.error.PrecheckBlockedException;
import com.javalearn.lab.chaos.error.ToxiproxyUnavailableException;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.OperationContext;
import com.javalearn.lab.chaos.ledger.OperationRecord;
import com.javalearn.lab.chaos.ledger.OperationStatus;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.metrics.PrometheusMatrix;
import com.javalearn.lab.chaos.metrics.PrometheusPoint;
import com.javalearn.lab.chaos.metrics.PrometheusQueryException;
import com.javalearn.lab.chaos.metrics.PrometheusSeries;
import com.javalearn.lab.chaos.precheck.PrecheckDockerFacts.LabContainerFacts;
import com.javalearn.lab.chaos.toxiproxy.ProxyStateView;
import com.javalearn.lab.chaos.toxiproxy.ToxicView;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyService;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyStateView;
import com.javalearn.lab.chaos.traffic.TrafficService;
import com.javalearn.lab.chaos.traffic.TrafficStateView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rule-by-rule tests of the B1-04 gate. Every collaborator is a real stand-in
 * with state (in-memory Docker facts, a fake traffic service, a fake Prometheus,
 * mock services returning real view records), so each test asserts the verdict
 * and the evidence of a concrete scenario - not that a method was called.
 */
class RunPrecheckServiceTest {

    private static final String RUN = "run-20261028-001";
    private static final String TRAFFIC = "jl-traffic";
    private static final Instant NOW = Instant.parse("2026-10-28T10:15:30Z");
    private static final long MB = 1024L * 1024L;

    private PrecheckProperties properties;
    private LedgerProperties ledgerProperties;
    private LedgerBootstrap bootstrap;
    private RunLedgerRepository repository;
    private FakeDockerFacts docker;
    private FakeTrafficService traffic;
    private ChaosActionService chaos;
    private ToxiproxyService toxiproxy;
    private ChaosProperties chaosProperties;
    private FakePrometheus prometheus;
    private RunPrecheckService service;

    @BeforeEach
    void setUp() {
        properties = new PrecheckProperties();
        ledgerProperties = new LedgerProperties();
        bootstrap = mock(LedgerBootstrap.class);
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.OK, "3", null, NOW));
        when(bootstrap.isReady()).thenReturn(true);

        repository = mock(RunLedgerRepository.class);
        when(repository.findRun(RUN)).thenReturn(Optional.of(new RunRecord(RUN, "spike",
                RunStatus.DRAFT, "commit", "local", NOW, NOW, null, null)));
        when(repository.findRecentOperationsByStatuses(any(), anyInt())).thenReturn(List.of());

        docker = new FakeDockerFacts();
        traffic = new FakeTrafficService();
        traffic.states.put(TRAFFIC, TrafficStateView.missing(TRAFFIC));

        chaos = mock(ChaosActionService.class);
        when(chaos.activeActions()).thenReturn(List.of());

        toxiproxy = mock(ToxiproxyService.class);
        when(toxiproxy.state()).thenReturn(cleanToxiproxy());

        chaosProperties = new ChaosProperties();
        prometheus = new FakePrometheus();
        prometheus.values.put(RunPrecheckService.PROMQL_APP_UP, 3.0);
        prometheus.values.put(RunPrecheckService.PROMQL_REDIS_EXPORTER_UP, 1.0);
        prometheus.values.put(RunPrecheckService.PROMQL_MYSQL_PROBE_UP, 1.0);

        service = newService();
    }

    // ------------------------------------------------------------------ all clean

    @Test
    void aCleanEnvironmentPassesEveryPublishedCheck() {
        PrecheckReport report = service.inspectReport(RUN);

        assertThat(report.verdict()).isEqualTo("PASS");
        assertThat(report.items()).extracting(PrecheckItem::key).containsExactly(
                RunPrecheckService.KEY_TRAFFIC,
                RunPrecheckService.KEY_CHAOS,
                RunPrecheckService.KEY_TOXIPROXY,
                RunPrecheckService.KEY_OPERATIONS,
                RunPrecheckService.KEY_MONITORING,
                RunPrecheckService.KEY_RESOURCES,
                RunPrecheckService.KEY_IMAGES);
        assertThat(report.items()).allMatch(item -> item.status() == PrecheckStatus.PASS);
        assertThat(report.blocking()).isEmpty();
        assertThat(service.enforce(RUN).verdict()).isEqualTo("PASS");
    }

    @Test
    void theGateCanBeDisabledAsAWholeButThenChecksNothing() {
        properties.setEnabled(false);
        traffic.states.put(TRAFFIC, new TrafficStateView(TRAFFIC, "running", "none", true));

        PrecheckReport report = service.inspectReport(RUN);

        assertThat(report.verdict()).isEqualTo("PASS");
        assertThat(report.items()).isEmpty();
        assertThat(service.enforce(RUN).verdict()).isEqualTo("PASS");
    }

    @Test
    void anUnknownRunIs404EvenWhenTheGateIsDisabled() {
        when(repository.findRun("missing")).thenReturn(Optional.empty());
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.inspectReport("missing"))
                .isInstanceOf(LedgerRunNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void aLedgerFailureIs503NotAWarning() {
        when(repository.findRun(RUN))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatThrownBy(() -> service.inspectReport(RUN))
                .isInstanceOf(LedgerUnavailableException.class)
                .hasMessageContaining("connection refused");
    }

    // ------------------------------------------------------------------- traffic

    @Test
    void runningBackgroundTrafficBlocksAndNamesTheContainer() {
        traffic.states.put(TRAFFIC, new TrafficStateView(TRAFFIC, "running", "none", true));

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_TRAFFIC);

        assertThat(item.status()).isEqualTo(PrecheckStatus.BLOCK);
        assertThat(item.message()).contains(TRAFFIC).contains("running");
        assertThat(item.detail()).containsEntry("container", TRAFFIC);
        assertThatThrownBy(() -> service.enforce(RUN))
                .isInstanceOf(PrecheckBlockedException.class)
                .hasMessageContaining("traffic.stopped");
    }

    @Test
    void aStoppedOrNeverCreatedTrafficContainerPasses() {
        traffic.states.put(TRAFFIC, new TrafficStateView(TRAFFIC, "exited", "none", false));
        assertThat(item(service.inspectReport(RUN), RunPrecheckService.KEY_TRAFFIC).status())
                .isEqualTo(PrecheckStatus.PASS);

        traffic.states.put(TRAFFIC, TrafficStateView.missing(TRAFFIC));
        assertThat(item(service.inspectReport(RUN), RunPrecheckService.KEY_TRAFFIC).message())
                .contains("not installed");
    }

    @Test
    void unverifiableBackgroundTrafficBlocksInsteadOfAssumingItIsStopped() {
        traffic.failure = new DockerUnavailableException("daemon refused");

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_TRAFFIC);

        assertThat(item.status()).isEqualTo(PrecheckStatus.BLOCK);
        assertThat(item.message()).contains("could not verify");
    }

    // --------------------------------------------------------------------- chaos

    @Test
    void anActiveChaosActionBlocksWithTargetActionAndRemainingTtl() {
        when(chaos.activeActions()).thenReturn(List.of(new ChaosActionView("a-1", "jl-app-2",
                "freeze", 180, NOW.plusSeconds(120), "resume", "ACTIVE", null)));

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_CHAOS);

        assertThat(item.status()).isEqualTo(PrecheckStatus.BLOCK);
        assertThat(item.message()).contains("jl-app-2").contains("freeze");
        assertThat(item.detail()).containsEntry("activeActions", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> action = ((List<Map<String, Object>>) item.detail().get("actions")).get(0);
        assertThat(action).containsEntry("target", "jl-app-2")
                .containsEntry("action", "freeze")
                .containsEntry("remainingTtlSeconds", 120L);
    }

    // ---------------------------------------------------------------- toxiproxy

    @Test
    void aToxicBlocksButADisabledOrUnreachableLayerOnlyWarns() {
        when(toxiproxy.state()).thenReturn(new ToxiproxyStateView(true, List.of("redis"),
                List.of(new ProxyStateView("redis", true, true,
                        List.of(new ToxicView("latency", "latency", Map.of("latency_ms", 500), 1.0))))));
        assertThat(item(service.inspectReport(RUN), RunPrecheckService.KEY_TOXIPROXY).status())
                .isEqualTo(PrecheckStatus.BLOCK);

        when(toxiproxy.state()).thenThrow(new ToxiproxyUnavailableException("connection refused"));
        PrecheckItem unreachable = item(service.inspectReport(RUN), RunPrecheckService.KEY_TOXIPROXY);
        assertThat(unreachable.status()).isEqualTo(PrecheckStatus.WARN);
        assertThat(unreachable.message()).contains("unreachable");

        chaosProperties.getToxiproxy().setEnabled(false);
        PrecheckItem disabled = item(service.inspectReport(RUN), RunPrecheckService.KEY_TOXIPROXY);
        assertThat(disabled.status()).isEqualTo(PrecheckStatus.WARN);
        assertThat(disabled.message()).contains("disabled");
    }

    @Test
    void aDisabledProxyIsResidualFaultAndBlocks() {
        when(toxiproxy.state()).thenReturn(new ToxiproxyStateView(true, List.of("redis"),
                List.of(new ProxyStateView("redis", true, false, List.of()))));

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_TOXIPROXY);

        assertThat(item.status()).isEqualTo(PrecheckStatus.BLOCK);
        assertThat(item.detail()).containsEntry("disabledProxies", List.of("redis"));
    }

    // --------------------------------------------------------------- operations

    @Test
    void unsettledOperationsBlockUnlessTheyAreThisStartRequestItself() {
        OperationRecord ownStart = new OperationRecord("op-own", RUN, "run", RUN, "start",
                OperationStatus.REQUESTED, null, NOW, null, null, null, "local", null, null);
        when(repository.findRecentOperationsByStatuses(any(), anyInt()))
                .thenReturn(List.of(ownStart));

        // The report endpoint does not ignore anything.
        assertThat(item(service.inspectReport(RUN), RunPrecheckService.KEY_OPERATIONS).status())
                .isEqualTo(PrecheckStatus.BLOCK);
        // The start path inserted this row itself, so it must not block on itself.
        assertThat(service.enforce(RUN).verdict()).isEqualTo("PASS");

        OperationRecord unknownStop = new OperationRecord("op-stuck", "run-20261028-002", "run",
                "run-20261028-002", "stop", OperationStatus.UNKNOWN, null, NOW, null, null,
                "no confirmation", "local", null, null);
        when(repository.findRecentOperationsByStatuses(any(), anyInt()))
                .thenReturn(List.of(ownStart, unknownStop));

        PrecheckBlockedException refused = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.enforce(RUN), PrecheckBlockedException.class);
        PrecheckItem item = item(refused.report(), RunPrecheckService.KEY_OPERATIONS);
        assertThat(item.status()).isEqualTo(PrecheckStatus.BLOCK);
        assertThat(item.message()).contains("op-stuck");
        assertThat(item.detail()).containsEntry("unsettledOperations", 1);
    }

    @Test
    void theExecutingOperationIsIgnoredEvenAfterItFlippedToPendingConfirmation() {
        OperationRecord ownPending = new OperationRecord("op-own", RUN, "run", RUN, "start",
                OperationStatus.PENDING_CONFIRMATION, null, NOW, null, null, null, "local",
                null, null);
        when(repository.findRecentOperationsByStatuses(any(), anyInt()))
                .thenReturn(List.of(ownPending));

        assertThat(OperationContext.withOperation("op-own", () -> service.enforce(RUN)).verdict())
                .isEqualTo("PASS");

        OperationRecord otherPending = new OperationRecord("op-other", "run-20261028-002", "run",
                "run-20261028-002", "stop", OperationStatus.PENDING_CONFIRMATION, null, NOW,
                null, null, null, "local", null, null);
        when(repository.findRecentOperationsByStatuses(any(), anyInt()))
                .thenReturn(List.of(ownPending, otherPending));

        assertThatThrownBy(() ->
                OperationContext.withOperation("op-own", () -> service.enforce(RUN)))
                .isInstanceOf(PrecheckBlockedException.class);
    }

    // --------------------------------------------------------------- monitoring

    @Test
    void aMissingAppTargetBlocksMonitoring() {
        prometheus.values.put(RunPrecheckService.PROMQL_APP_UP, 2.0);

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_MONITORING);

        assertThat(item.status()).isEqualTo(PrecheckStatus.BLOCK);
        assertThat(item.message()).contains("seckill-app").contains("expected 3");
    }

    @Test
    void missingRedisExporterOrMysqlProbeBlocksMonitoring() {
        prometheus.values.remove(RunPrecheckService.PROMQL_REDIS_EXPORTER_UP);
        assertThat(item(service.inspectReport(RUN), RunPrecheckService.KEY_MONITORING).message())
                .contains("redis-exporter");

        prometheus.values.put(RunPrecheckService.PROMQL_REDIS_EXPORTER_UP, 1.0);
        prometheus.values.put(RunPrecheckService.PROMQL_MYSQL_PROBE_UP, 0.0);
        assertThat(item(service.inspectReport(RUN), RunPrecheckService.KEY_MONITORING).message())
                .contains("mysql_cluster_probe_up");
    }

    @Test
    void monitoringRequirementsCanBeTunedPerStack() {
        properties.setRequireRedisExporter(false);
        properties.setRequireMysqlProbe(false);
        prometheus.values.remove(RunPrecheckService.PROMQL_REDIS_EXPORTER_UP);
        prometheus.values.put(RunPrecheckService.PROMQL_MYSQL_PROBE_UP, 0.0);

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_MONITORING);

        assertThat(item.status()).isEqualTo(PrecheckStatus.PASS);
        assertThat(item.detail()).containsEntry("redisExporterUp", "not-required")
                .containsEntry("mysqlClusterProbeUp", "not-required");
    }

    @Test
    void anExtraRequiredQueryWithoutSeriesBlocksMonitoring() {
        properties.setExtraQueries("redis-ha=sum(up{job=\"redis-exporter-ha\"}) >= 1");

        PrecheckItem blocked = item(service.inspectReport(RUN), RunPrecheckService.KEY_MONITORING);
        assertThat(blocked.status()).isEqualTo(PrecheckStatus.BLOCK);
        assertThat(blocked.message()).contains("redis-ha");

        prometheus.values.put("sum(up{job=\"redis-exporter-ha\"}) >= 1", 1.0);
        PrecheckItem passed = item(service.inspectReport(RUN), RunPrecheckService.KEY_MONITORING);
        assertThat(passed.status()).isEqualTo(PrecheckStatus.PASS);
        assertThat(passed.detail()).containsEntry("extra.redis-ha", 1.0);
    }

    @Test
    void anUnreachablePrometheusBlocksBecauseThereIsNoDataToVerify() {        prometheus.failure = new PrometheusQueryException("prometheus unreachable at http://prometheus:9090");

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_MONITORING);

        assertThat(item.status()).isEqualTo(PrecheckStatus.BLOCK);
        assertThat(item.message()).contains("Prometheus is unreachable");
        assertThat(item.detail()).containsEntry("prometheusReachable", false);
    }

    // ---------------------------------------------------------------- resources

    @Test
    void theMemoryBudgetBlocksWhenLimitsPlusReserveExceedTheHost() {
        docker.totalBytes = 2048L * MB; // limits 1792MB + reserve 1024MB

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_RESOURCES);

        assertThat(item.status()).isEqualTo(PrecheckStatus.BLOCK);
        assertThat(item.message()).contains("budget exceeded");
        assertThat(item.detail()).containsEntry("limitTotalMb", 1792L)
                .containsEntry("reserveMb", 1024)
                .containsEntry("hostTotalMb", 2048L)
                .containsEntry("overByMb", 768L);
    }

    @Test
    void aBudgetThatFitsExactlyPasses() {
        docker.totalBytes = 2816L * MB; // 1792MB limits + 1024MB reserve

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_RESOURCES);

        assertThat(item.status()).isEqualTo(PrecheckStatus.PASS);
        assertThat(item.detail()).containsEntry("overByMb", 0L);
    }

    @Test
    void unavailableDockerMemoryOnlyWarns() {
        docker.failure = new DockerUnavailableException("daemon down");

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_RESOURCES);

        assertThat(item.status()).isEqualTo(PrecheckStatus.WARN);
        assertThat(item.message()).contains("unavailable");
    }

    @Test
    void aContainerWithoutAMemoryLimitMakesTheSumUnboundedSoItWarns() {
        docker.running.add(new LabContainerFacts("jl-unbounded", "sha256:x", 0L, "running"));

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_RESOURCES);

        assertThat(item.status()).isEqualTo(PrecheckStatus.WARN);
        assertThat(item.message()).contains("no mem_limit");
        assertThat(item.detail()).containsEntry("unboundedContainers", 1);
    }

    // ------------------------------------------------------------------- images

    @Test
    void differingAppImagesBlock() {
        docker.containers.put("jl-app-3",
                new LabContainerFacts("jl-app-3", "sha256:different", 512L * MB, "running"));

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_IMAGES);

        assertThat(item.status()).isEqualTo(PrecheckStatus.BLOCK);
        assertThat(item.message()).contains("different images");
    }

    @Test
    void aConfiguredExpectedImageMustMatchTheRunningIds() {
        properties.setExpectedAppImageId("sha256:expected");

        assertThat(item(service.inspectReport(RUN), RunPrecheckService.KEY_IMAGES).status())
                .isEqualTo(PrecheckStatus.BLOCK);

        properties.setExpectedAppImageId("sha256:app"); // prefix of sha256:app-image
        assertThat(item(service.inspectReport(RUN), RunPrecheckService.KEY_IMAGES).status())
                .isEqualTo(PrecheckStatus.PASS);
    }

    @Test
    void fewerRunningAppsOnlyWarnsAndAnInspectionFailureWarns() {
        docker.containers.put("jl-app-2",
                new LabContainerFacts("jl-app-2", "sha256:app-image", 512L * MB, "exited"));

        assertThat(item(service.inspectReport(RUN), RunPrecheckService.KEY_IMAGES).status())
                .isEqualTo(PrecheckStatus.WARN);

        docker.failure = new DockerUnavailableException("daemon down");
        assertThat(item(service.inspectReport(RUN), RunPrecheckService.KEY_IMAGES).status())
                .isEqualTo(PrecheckStatus.WARN);
    }

    @Test
    void aMissingAppContainerIsNotAnImageMismatch() {
        docker.missing.add("jl-app-3");

        PrecheckItem item = item(service.inspectReport(RUN), RunPrecheckService.KEY_IMAGES);

        assertThat(item.status()).isEqualTo(PrecheckStatus.WARN);
        assertThat(item.detail()).containsEntry("missingContainers", 1);
    }

    // ---------------------------------------------------------------- helpers

    private RunPrecheckService newService() {
        return new RunPrecheckService(properties, ledgerProperties, bootstrap, repository, docker,
                traffic, chaos, toxiproxy, chaosProperties, prometheus,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PrecheckItem item(PrecheckReport report, String key) {
        return report.items().stream().filter(entry -> entry.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no item " + key + " in " + report.items()));
    }

    private static ToxiproxyStateView cleanToxiproxy() {
        return new ToxiproxyStateView(true, List.of("redis"),
                List.of(new ProxyStateView("redis", true, true, List.of())));
    }

    /** In-memory Docker facts: three running apps on the same image, 4 x 512MB limits. */
    private static final class FakeDockerFacts implements PrecheckDockerFacts {

        private final Map<String, LabContainerFacts> containers = new LinkedHashMap<>();
        private final List<LabContainerFacts> running = new java.util.ArrayList<>();
        private final List<String> missing = new java.util.ArrayList<>();
        private long totalBytes = 16L * 1024L * MB;
        private RuntimeException failure;

        private FakeDockerFacts() {
            for (String app : List.of("jl-app-1", "jl-app-2", "jl-app-3")) {
                containers.put(app, new LabContainerFacts(app, "sha256:app-image", 512L * MB, "running"));
            }
            running.add(new LabContainerFacts("jl-app-1", "sha256:app-image", 512L * MB, "running"));
            running.add(new LabContainerFacts("jl-app-2", "sha256:app-image", 512L * MB, "running"));
            running.add(new LabContainerFacts("jl-chaos-controller", "sha256:chaos", 256L * MB, "running"));
            running.add(new LabContainerFacts("jl-prometheus", "sha256:prom", 512L * MB, "running"));
            // 512 + 512 + 256 + 512 = 1792MB; plus the 1024MB reserve.
        }

        @Override
        public List<LabContainerFacts> runningLabContainers() {
            if (failure != null) {
                throw failure;
            }
            return List.copyOf(running);
        }

        @Override
        public LabContainerFacts inspect(String containerName) {
            if (failure != null) {
                throw failure;
            }
            if (missing.contains(containerName)) {
                throw new ContainerNotFoundException("Container not found: " + containerName);
            }
            LabContainerFacts facts = containers.get(containerName);
            if (facts == null) {
                throw new ContainerNotFoundException("Container not found: " + containerName);
            }
            return facts;
        }

        @Override
        public long memoryTotalBytes() {
            if (failure != null) {
                throw failure;
            }
            return totalBytes;
        }
    }

    /** Traffic state without Docker; the only method the gate uses is overridden. */
    private static final class FakeTrafficService extends TrafficService {

        private final Map<String, TrafficStateView> states = new LinkedHashMap<>();
        private RuntimeException failure;

        private FakeTrafficService() {
            super(null, new ChaosProperties());
        }

        @Override
        public TrafficStateView state(String container) {
            if (failure != null) {
                throw failure;
            }
            return states.getOrDefault(container, TrafficStateView.missing(container));
        }
    }

    /** Instant-query stand-in: a value per PromQL string, or a failure. */
    private static final class FakePrometheus implements PrometheusGateway {

        private final Map<String, Double> values = new LinkedHashMap<>();
        private RuntimeException failure;

        @Override
        public PrometheusMatrix queryRange(String query, long startEpochSeconds, long endEpochSeconds,
                                           long stepSeconds) {
            throw new UnsupportedOperationException("the gate uses instant queries only");
        }

        @Override
        public PrometheusMatrix queryInstant(String query, long atEpochSeconds) {
            if (failure != null) {
                throw failure;
            }
            Double value = values.get(query);
            if (value == null) {
                return new PrometheusMatrix(List.of());
            }
            return new PrometheusMatrix(List.of(new PrometheusSeries(Map.of("job", "test"),
                    List.of(new PrometheusPoint(atEpochSeconds, value)))));
        }
    }

    @Test
    void scopedAppQueryMatchesOnlyEnabledNodeInstances() {
        assertThat(RunPrecheckService.appUpQuery(java.util.List.of("app-1", "app-2")))
                .isEqualTo("sum(up{job=\"seckill-app\",instance=~\"(app-1|app-2):.*\"})");
    }
}
