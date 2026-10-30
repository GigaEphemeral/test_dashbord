package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.HealthCheck;
import com.github.dockerjava.api.model.HostConfig;
import com.javalearn.lab.chaos.error.InstanceConfigConflictException;
import com.javalearn.lab.chaos.error.InstanceRebuildConflictException;
import com.javalearn.lab.chaos.error.InvalidInstanceConfigException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.metrics.PrometheusMatrix;
import com.javalearn.lab.chaos.metrics.PrometheusPoint;
import com.javalearn.lab.chaos.metrics.PrometheusSeries;
import com.javalearn.lab.chaos.model.ContainerView;
import com.javalearn.lab.chaos.model.ExecResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B4-02 orchestration without Docker, HTTP or MySQL: a fake rebuild gateway owns
 * the call order, a fake probe answers the health gates, and an in-memory
 * repository owns the status transitions.
 */
class InstanceRebuildServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TARGET_JSON = """
            {"jdkVersion":21,"imageTag":"jdk21","xmsMb":256,"xmxMb":384,"gcCollector":"G1",
             "corePoolSize":2,"maxPoolSize":4,"queueCapacity":10,"keepAliveSeconds":30,
             "rejectionPolicy":"AbortPolicy","caffeineEnabled":true,"gcLogEnabled":false}
            """;
    private static final String BASELINE_JSON = """
            {"jdkVersion":21,"imageTag":"jdk21","xmsMb":256,"xmxMb":256,"gcCollector":"G1",
             "corePoolSize":4,"maxPoolSize":8,"queueCapacity":50,"keepAliveSeconds":60,
             "rejectionPolicy":"AbortPolicy","caffeineEnabled":true,"gcLogEnabled":false}
            """;

    private static final String POOL_TARGET = """
            {"code":"0","success":true,"data":{"instance":"app-1","corePoolSize":2,
             "maxPoolSize":4,"queueCapacity":10,"keepAliveSeconds":30,
             "rejectionPolicy":"AbortPolicy"}}
            """;
    private static final String POOL_BASELINE = """
            {"code":"0","success":true,"data":{"instance":"app-1","corePoolSize":4,
             "maxPoolSize":8,"queueCapacity":50,"keepAliveSeconds":60,
             "rejectionPolicy":"AbortPolicy"}}
            """;

    private InstanceConfigProperties properties;
    private InMemoryConfigRepository repository;
    private RunLedgerRepository runRepository;
    private FakeRebuildGateway gateway;
    private FakeContainers containers;
    private FakeProbe probe;
    private InstanceRebuildService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new InstanceConfigProperties();
        properties.validate();
        // Fast gates: one poll, no Prometheus wait.
        properties.setHealthTimeoutSeconds(1);
        properties.setPrometheusUpWaitSeconds(0);
        properties.setProbeTimeoutSeconds(1);

        repository = new InMemoryConfigRepository();
        runRepository = mock(RunLedgerRepository.class);
        when(runRepository.findRunsByStatuses(any())).thenReturn(List.of());
        LedgerBootstrap bootstrap = mock(LedgerBootstrap.class);
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.OK, "5", null, NOW));

        gateway = new FakeRebuildGateway();
        containers = new FakeContainers();
        probe = new FakeProbe();
        ObjectProvider<InstanceRebuildGateway> rebuildProvider = mock(ObjectProvider.class);
        when(rebuildProvider.getIfAvailable()).thenReturn(gateway);
        ObjectProvider<ContainerGateway> containerProvider = mock(ObjectProvider.class);
        when(containerProvider.getIfAvailable()).thenReturn(containers);

        service = new InstanceRebuildService(properties, new InstanceConfigValidator(properties),
                repository, runRepository, new LedgerProperties(), bootstrap, MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC), rebuildProvider, containerProvider,
                upOne(), probe, "lab-token");
    }

    @Test
    void applyRebuildsInOrderAndRecordsImageAndStablePeriod() throws Exception {
        store(TARGET_JSON, null, 1);

        InstanceViews.InstanceApplyView view = service.apply("app-1");

        assertThat(view.status()).isEqualTo(InstanceRebuildService.STATUS_APPLIED);
        assertThat(view.action()).isEqualTo(InstanceRebuildService.ACTION_APPLY);
        assertThat(view.rolledBack()).isFalse();
        assertThat(view.rollback()).isEqualTo(InstanceRebuildService.ROLLBACK_NOT_NEEDED);
        assertThat(view.image()).isEqualTo("java-learn-lab/seckill-app:jdk21");
        assertThat(view.imageId()).isEqualTo("sha256:new");
        assertThat(view.previousImage()).isEqualTo("java-learn-lab/seckill-app:jdk21");
        assertThat(view.previousImageId()).isEqualTo("sha256:old");
        assertThat(view.javaToolOptions()).contains("-Xmx384m");
        assertThat(view.stableUntil()).isEqualTo(NOW.plusSeconds(60).toString());
        assertThat(view.failedCheck()).isNull();
        assertThat(view.rollback()).isEqualTo(InstanceRebuildService.ROLLBACK_NOT_NEEDED);

        assertThat(gateway.calls).first().isEqualTo("snapshot");
        assertThat(gateway.calls.get(1)).isEqualTo("stopAndPark");
        assertThat(gateway.calls.get(2)).isEqualTo("create");
        assertThat(gateway.calls.get(3)).isEqualTo("start");
        assertThat(gateway.calls.get(4)).isEqualTo("reloadNginx");
        assertThat(gateway.calls).anyMatch(call -> call.startsWith("remove:jl-app-1-pre-"));

        assertThat(gateway.createdEnv.get("JAVA_TOOL_OPTIONS")).contains("-Xmx384m");
        assertThat(gateway.createdEnv.get("BUSINESS_POOL_CORE_SIZE")).isEqualTo("2");
        assertThat(gateway.createdEnv.get("BUSINESS_POOL_MAX_SIZE")).isEqualTo("4");
        assertThat(gateway.createdEnv.get("BUSINESS_POOL_QUEUE_CAPACITY")).isEqualTo("10");
        assertThat(gateway.createdEnv.get("BUSINESS_POOL_KEEP_ALIVE_SECONDS")).isEqualTo("30");
        assertThat(gateway.createdEnv.get("BUSINESS_POOL_REJECTION_POLICY")).isEqualTo("AbortPolicy");
        // Unrelated container env is copied verbatim.
        assertThat(gateway.createdEnv.get("INSTANCE_ID")).isEqualTo("app-1");

        assertThat(view.checks()).extracting(InstanceViews.ApplyCheckView::name).containsExactly(
                "snapshot", "park", "create", "start", "docker-health", "app-probe",
                "pool-probe", "nginx-reload", "prometheus-up", "image-identity");
        assertThat(view.checks()).allMatch(check ->
                InstanceRebuildService.CHECK_PASS.equals(check.status()));

        StoredInstanceConfig stored = repository.row;
        assertThat(stored.status()).isEqualTo(InstanceConfigStatus.APPLIED);
        assertThat(stored.appliedImageTag()).isEqualTo("java-learn-lab/seckill-app:jdk21");
        assertThat(stored.appliedImageId()).isEqualTo("sha256:new");
        assertThat(stored.previousImageId()).isEqualTo("sha256:old");
        assertThat(stored.appliedAt()).isEqualTo(NOW);
        assertThat(stored.stableUntil()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void appProbeFailureRestoresTheParkedContainerAndSetsTheStablePeriod() throws Exception {
        store(TARGET_JSON, null, 1);
        probe.failUrl = "http://app-1:8080/api/hello";

        InstanceViews.InstanceApplyView view = service.apply("app-1");

        assertThat(view.status()).isEqualTo(InstanceRebuildService.STATUS_FAILED);
        assertThat(view.rolledBack()).isTrue();
        assertThat(view.rollback()).isEqualTo(InstanceRebuildService.ROLLBACK_RESTORED);
        assertThat(view.failedCheck()).isEqualTo("app-probe");
        assertThat(view.error()).contains("app probe failed");
        assertThat(view.stableUntil()).isEqualTo(NOW.plusSeconds(60).toString());
        assertThat(view.checks()).anyMatch(check -> "rollback".equals(check.name())
                && InstanceRebuildService.CHECK_PASS.equals(check.status()));
        assertThat(gateway.calls).contains("remove:jl-app-1:true", "restore");

        assertThat(repository.row.status()).isEqualTo(InstanceConfigStatus.FAILED);
        assertThat(repository.row.stableUntil()).isEqualTo(NOW.plusSeconds(60));
        assertThat(repository.row.lastError()).contains("app-probe").contains("RESTORED");
        assertThat(repository.row.appliedImageId()).isEqualTo("sha256:old");
    }

    @Test
    void healthGateFailureRestoresTheParkedContainer() throws Exception {
        store(TARGET_JSON, null, 1);
        containers.health = "starting";

        InstanceViews.InstanceApplyView view = service.apply("app-1");

        assertThat(view.status()).isEqualTo(InstanceRebuildService.STATUS_FAILED);
        assertThat(view.failedCheck()).isEqualTo("docker-health");
        assertThat(view.rolledBack()).isTrue();
        assertThat(gateway.calls).contains("restore");
        assertThat(repository.row.status()).isEqualTo(InstanceConfigStatus.FAILED);
    }

    @Test
    void createFailureRestoresTheParkedContainer() throws Exception {
        store(TARGET_JSON, null, 1);
        gateway.failCreate = true;

        InstanceViews.InstanceApplyView view = service.apply("app-1");

        assertThat(view.status()).isEqualTo(InstanceRebuildService.STATUS_FAILED);
        assertThat(view.failedCheck()).isEqualTo("create");
        assertThat(view.rolledBack()).isTrue();
        assertThat(gateway.calls).containsSubsequence("stopAndPark", "create", "restore");
        assertThat(repository.row.status()).isEqualTo(InstanceConfigStatus.FAILED);
        assertThat(repository.row.stableUntil()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void failedRestoreMarksTheInstanceDegraded() throws Exception {
        store(TARGET_JSON, null, 1);
        containers.health = "starting";
        gateway.failRestore = true;

        InstanceViews.InstanceApplyView view = service.apply("app-1");

        assertThat(view.status()).isEqualTo(InstanceRebuildService.STATUS_DEGRADED);
        assertThat(view.rolledBack()).isFalse();
        assertThat(view.rollback()).isEqualTo(InstanceRebuildService.ROLLBACK_FAILED);
        assertThat(repository.row.status()).isEqualTo(InstanceConfigStatus.DEGRADED);
    }

    @Test
    void runningRunRefusesTheApplyBeforeAnyDockerCall() throws Exception {
        store(TARGET_JSON, null, 1);
        when(runRepository.findRunsByStatuses(any())).thenReturn(List.of(new RunRecord(
                "run-20261030-001", "spike", RunStatus.RUNNING, "commit", "local",
                NOW, NOW, NOW, null)));

        assertThatThrownBy(() -> service.apply("app-1"))
                .isInstanceOf(InstanceConfigConflictException.class)
                .hasMessageContaining("run-20261030-001")
                .hasMessageContaining("RUNNING");
        assertThat(gateway.calls).isEmpty();
    }

    @Test
    void noStoredConfigRefusesTheApply() {
        assertThatThrownBy(() -> service.apply("app-1"))
                .isInstanceOf(InstanceRebuildConflictException.class)
                .hasMessageContaining("no stored configuration");
        assertThat(gateway.calls).isEmpty();
    }

    @Test
    void jdk8ImageIsRefusedForTheAppTier() throws Exception {
        store("""
                {"jdkVersion":8,"imageTag":"jdk8","xmxMb":256}
                """, null, 1);

        assertThatThrownBy(() -> service.apply("app-1"))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("jdk8")
                .hasMessageContaining("JDK 8 slot");
        assertThat(gateway.calls).isEmpty();
    }

    @Test
    void rollbackRebuildsFromPreviousConfigAndSwapsSoItToggles() throws Exception {
        store(TARGET_JSON, BASELINE_JSON, 2);
        probe.poolJson = POOL_BASELINE;

        InstanceViews.InstanceApplyView first = service.rollback("app-1");
        assertThat(first.status()).isEqualTo(InstanceRebuildService.STATUS_ROLLED_BACK);
        assertThat(first.action()).isEqualTo(InstanceRebuildService.ACTION_ROLLBACK);
        assertThat(gateway.createdEnv.get("JAVA_TOOL_OPTIONS")).contains("-Xmx256m");
        assertThat(json(repository.row.configJson()).path("xmxMb").asInt()).isEqualTo(256);
        assertThat(json(repository.row.previousConfigJson()).path("xmxMb").asInt()).isEqualTo(384);
        assertThat(repository.row.revision()).isEqualTo(3);

        // The config is now the baseline; the old target became "previous".
        probe.poolJson = POOL_TARGET;
        gateway.createdEnv.clear();
        InstanceViews.InstanceApplyView second = service.rollback("app-1");
        assertThat(second.status()).isEqualTo(InstanceRebuildService.STATUS_ROLLED_BACK);
        assertThat(gateway.createdEnv.get("JAVA_TOOL_OPTIONS")).contains("-Xmx384m");
        assertThat(json(repository.row.configJson()).path("xmxMb").asInt()).isEqualTo(384);
        assertThat(json(repository.row.previousConfigJson()).path("xmxMb").asInt()).isEqualTo(256);
    }

    @Test
    void rollbackWithoutPreviousConfigIs409() throws Exception {
        store(TARGET_JSON, null, 1);

        assertThatThrownBy(() -> service.rollback("app-1"))
                .isInstanceOf(InstanceRebuildConflictException.class)
                .hasMessageContaining("no previous configuration");
        assertThat(gateway.calls).isEmpty();
    }

    @Test
    void poolMismatchFailsAndRollsBack() throws Exception {
        store(TARGET_JSON, null, 1);
        probe.poolJson = POOL_BASELINE;

        InstanceViews.InstanceApplyView view = service.apply("app-1");

        assertThat(view.status()).isEqualTo(InstanceRebuildService.STATUS_FAILED);
        assertThat(view.failedCheck()).isEqualTo("pool-probe");
        assertThat(view.error()).contains("corePoolSize expected 2");
        assertThat(view.rolledBack()).isTrue();
    }

    @Test
    void nginxProbeAndPrometheusGatesAreEnforced() throws Exception {
        store(TARGET_JSON, null, 1);
        probe.failUrl = "http://nginx/api/hello";
        InstanceViews.InstanceApplyView nginxFailure = service.apply("app-1");
        assertThat(nginxFailure.failedCheck()).isEqualTo("nginx-reload");

        gateway.calls.clear();
        probe.failUrl = null;
        InstanceViews.InstanceApplyView ok = service.apply("app-1");
        assertThat(ok.status()).isEqualTo(InstanceRebuildService.STATUS_APPLIED);
    }

    @Test
    void ledgerOutcomeWriteFailureKeepsTheHealthyRebuildAndReportsTheError() throws Exception {
        store(TARGET_JSON, null, 1);
        repository.failMarkApplied = true;

        InstanceViews.InstanceApplyView view = service.apply("app-1");

        // A healthy rebuild is never rolled back because the ledger hiccuped:
        // no restore, the parked container is removed and the outcome is APPLIED
        // with the ledger problem surfaced in the evidence, not hidden.
        assertThat(view.status()).isEqualTo(InstanceRebuildService.STATUS_APPLIED);
        assertThat(view.rollback()).isEqualTo(InstanceRebuildService.ROLLBACK_NOT_NEEDED);
        assertThat(view.error()).contains("ledger outcome write failed");
        assertThat(gateway.calls).doesNotContain("restore");
        assertThat(gateway.calls).anyMatch(call -> call.startsWith("remove:jl-app-1-pre-"));
        // The durable row keeps the APPLYING marker (the outcome write failed);
        // the console reads the truth from the response evidence.
        assertThat(repository.row.status()).isEqualTo(InstanceConfigStatus.APPLYING);
    }

    @Test
    void failedRebuildStillReportsFailedWhenTheLedgerWriteFails() throws Exception {
        store(TARGET_JSON, null, 1);
        probe.failUrl = "http://app-1:8080/api/hello";
        repository.failMarkFailed = true;

        InstanceViews.InstanceApplyView view = service.apply("app-1");

        assertThat(view.status()).isEqualTo(InstanceRebuildService.STATUS_FAILED);
        assertThat(view.error()).contains("app probe failed")
                .contains("ledger outcome write failed");
        assertThat(gateway.calls).contains("restore");
    }

    // ---------------------------------------------------------------- helpers

    private void store(String configJson, String previousJson, int revision) {
        repository.row = new StoredInstanceConfig("app-1", configJson, previousJson,
                InstanceConfigStatus.DRAFT, revision, NOW, null, null, null, null, null, null,
                null, null);
    }

    private void storeStatus(String configJson, InstanceConfigStatus status) {
        repository.row = new StoredInstanceConfig("app-1", configJson, null, status, 1, NOW,
                NOW, null, null, null, null, null, null, null);
    }

    @Test
    void restartRebuildsWithTheAppliedConfig() {
        storeStatus(TARGET_JSON, InstanceConfigStatus.APPLIED);

        InstanceViews.InstanceApplyView view = service.restart("app-1");

        assertThat(view.action()).isEqualTo("RESTART");
        assertThat(view.status()).isEqualTo(InstanceRebuildService.STATUS_APPLIED);
        assertThat(view.imageId()).isEqualTo("sha256:new");
        assertThat(view.rolledBack()).isFalse();
        assertThat(repository.row.status()).isEqualTo(InstanceConfigStatus.APPLIED);
        assertThat(gateway.calls).isNotEmpty();
    }

    @Test
    void restartWithoutAppliedConfigIs409() {
        store(TARGET_JSON, null, 1);

        assertThatThrownBy(() -> service.restart("app-1"))
                .isInstanceOf(InstanceRebuildConflictException.class)
                .hasMessageContaining("APPLIED");
        assertThat(gateway.calls).isEmpty();
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PrometheusGateway upOne() {
        return new PrometheusGateway() {
            @Override
            public PrometheusMatrix queryRange(String query, long startEpochSeconds,
                                               long endEpochSeconds, long stepSeconds) {
                return queryInstant(query, endEpochSeconds);
            }

            @Override
            public PrometheusMatrix queryInstant(String query, long atEpochSeconds) {
                return new PrometheusMatrix(List.of(new PrometheusSeries(
                        Map.of("job", "seckill-app", "instance", "app-1:8080"),
                        List.of(new PrometheusPoint(atEpochSeconds, 1.0d)))));
            }
        };
    }

    /** Fake rebuild gateway: records the order and simulates container identity. */
    private static final class FakeRebuildGateway implements InstanceRebuildGateway {

        private final List<String> calls = new ArrayList<>();
        private final Map<String, String> createdEnv = new HashMap<>();
        private String imageRef = "java-learn-lab/seckill-app:jdk21";
        private String imageId = "sha256:old";
        private boolean failCreate;
        private boolean failRestore;

        @Override
        public InstanceSnapshot snapshot(String instance) {
            calls.add("snapshot");
            return new InstanceSnapshot(instance, "jl-app-1", "old-id", imageRef, imageId,
                    Map.of("JAVA_TOOL_OPTIONS", "-Xmx512m", "INSTANCE_ID", "app-1",
                            "MYSQL_PASSWORD", "must-not-leak"),
                    Map.of("lab", "java-learn"), List.of(), List.of(), "/app", "app", "old-id",
                    List.of("8080/tcp"), Map.of("jl-net", List.of("jl-app-1", "app-1")),
                    new HealthCheck().withTest(List.of("CMD-SHELL", "true")).withInterval(5_000_000_000L),
                    HostConfig.newHostConfig().withNetworkMode("jl-net"));
        }

        @Override
        public boolean imageExists(String imageRef) {
            return true;
        }

        @Override
        public String imageId(String imageRef) {
            return "sha256:new";
        }

        @Override
        public void stopAndPark(String instance, String parkName) {
            calls.add("stopAndPark");
        }

        @Override
        public String createFrom(InstanceSnapshot snapshot, Map<String, String> env,
                                 String imageRef, String name) {
            calls.add("create");
            createdEnv.putAll(env);
            if (failCreate) {
                throw new IllegalStateException("docker create failed");
            }
            this.imageRef = imageRef;
            this.imageId = "sha256:new";
            return "new-id";
        }

        @Override
        public void start(String containerName) {
            calls.add("start");
        }

        @Override
        public void remove(String containerName, boolean force) {
            calls.add("remove:" + containerName + ":" + force);
        }

        @Override
        public void rename(String containerName, String newName) {
            calls.add("rename:" + newName);
        }

        @Override
        public void restore(String parkName, String originalName) {
            calls.add("restore");
            if (failRestore) {
                throw new IllegalStateException("restore failed");
            }
            imageRef = "java-learn-lab/seckill-app:jdk21";
            imageId = "sha256:old";
        }

        @Override
        public void reloadNginx() {
            calls.add("reloadNginx");
        }
    }

    private static final class FakeContainers implements ContainerGateway {

        private String health = "healthy";

        @Override
        public List<ContainerView> listLabContainers() {
            return List.of();
        }

        @Override
        public String tailLogs(String containerName, int tail) {
            return "";
        }

        @Override
        public ContainerView inspectLabContainer(String containerName) {
            return new ContainerView(containerName, "running", health);
        }

        @Override
        public void stop(String containerName, int graceSeconds) {
        }

        @Override
        public void start(String containerName) {
        }

        @Override
        public void restart(String containerName, int graceSeconds) {
        }

        @Override
        public void signal(String containerName, String signal) {
        }

        @Override
        public ExecResult execInContainer(String containerName, List<String> command,
                                          List<String> env, String stdin, int timeoutSeconds) {
            return new ExecResult("", "", 0, false);
        }
    }

    private static final class FakeProbe implements InstanceProbeGateway {

        private String failUrl;
        private String poolJson = POOL_TARGET;

        @Override
        public ProbeResult get(String url, String labToken, int timeoutSeconds) {
            if (url.equals(failUrl)) {
                return new ProbeResult(500, "boom", null);
            }
            if (url.endsWith("/lab/pool")) {
                return new ProbeResult(200, poolJson, null);
            }
            return new ProbeResult(200, "{\"message\":\"hello\"}", null);
        }
    }

    /** In-memory stand-in that owns every B4-01/B4-02 transition. */
    private static final class InMemoryConfigRepository extends InstanceConfigRepository {

        private StoredInstanceConfig row;
        private boolean failMarkApplied;
        private boolean failMarkFailed;

        private InMemoryConfigRepository() {
            super(null);
        }

        @Override
        public Optional<StoredInstanceConfig> find(String instance) {
            return row != null && row.instance().equals(instance) ? Optional.of(row) : Optional.empty();
        }

        @Override
        public StoredInstanceConfig upsertDraft(String instance, String configJson, Instant now) {
            row = new StoredInstanceConfig(instance, configJson,
                    row == null ? null : row.configJson(), InstanceConfigStatus.DRAFT,
                    row == null ? 1 : row.revision() + 1, now, null, null, null, null, null, null,
                    null, null);
            return row;
        }

        @Override
        public int markApplying(String instance, Instant now) {
            row = new StoredInstanceConfig(row.instance(), row.configJson(),
                    row.previousConfigJson(), InstanceConfigStatus.APPLYING, row.revision(), now,
                    null, null, row.stableUntil(), row.appliedImageTag(), row.appliedImageId(),
                    row.lastApplyAt(), row.previousImageTag(), row.previousImageId());
            return 1;
        }

        @Override
        public int markApplied(String instance, Instant appliedAt) {
            return markApplied(instance, null, null, null, null, appliedAt, null);
        }

        @Override
        public int markApplied(String instance, String appliedImageTag, String appliedImageId,
                               String previousImageTag, String previousImageId, Instant appliedAt,
                               Instant stableUntil) {
            if (failMarkApplied) {
                throw new org.springframework.dao.DataRetrievalFailureException(
                        "simulated ledger outage");
            }
            row = new StoredInstanceConfig(row.instance(), row.configJson(),
                    row.previousConfigJson(), InstanceConfigStatus.APPLIED, row.revision(),
                    appliedAt, appliedAt, null, stableUntil, appliedImageTag, appliedImageId,
                    appliedAt, previousImageTag, previousImageId);
            return 1;
        }

        @Override
        public int markFailed(String instance, String error, Instant now) {
            return markFailed(instance, error, now, null, null, null);
        }

        @Override
        public int markFailed(String instance, String error, Instant now, Instant stableUntil,
                              String restoredImageTag, String restoredImageId) {
            if (failMarkFailed) {
                throw new org.springframework.dao.DataRetrievalFailureException(
                        "simulated ledger outage");
            }
            row = new StoredInstanceConfig(row.instance(), row.configJson(),
                    row.previousConfigJson(), InstanceConfigStatus.FAILED, row.revision(), now,
                    null, error, stableUntil, restoredImageTag, restoredImageId, now,
                    row.previousImageTag(), row.previousImageId());
            return 1;
        }

        @Override
        public int markDegraded(String instance, String error, Instant now, Instant stableUntil) {
            row = new StoredInstanceConfig(row.instance(), row.configJson(),
                    row.previousConfigJson(), InstanceConfigStatus.DEGRADED, row.revision(), now,
                    null, error, stableUntil, row.appliedImageTag(), row.appliedImageId(), now,
                    row.previousImageTag(), row.previousImageId());
            return 1;
        }

        @Override
        public int markRolledBack(String instance, String configJson, String previousConfigJson,
                                  String appliedImageTag, String appliedImageId,
                                  String previousImageTag, String previousImageId, Instant appliedAt,
                                  Instant stableUntil) {
            row = new StoredInstanceConfig(row.instance(), configJson, previousConfigJson,
                    InstanceConfigStatus.APPLIED, row.revision() + 1, appliedAt, appliedAt, null,
                    stableUntil, appliedImageTag, appliedImageId, appliedAt, previousImageTag,
                    previousImageId);
            return 1;
        }
    }
}
