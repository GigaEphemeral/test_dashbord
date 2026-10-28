package com.javalearn.lab.chaos.bench.exec;

import com.javalearn.lab.chaos.error.BenchReportNotFoundException;
import com.javalearn.lab.chaos.error.BenchReportTooLargeException;
import com.javalearn.lab.chaos.error.BenchRunConflictException;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executor tests (Stage2 B2-02b-1): fake gateway + a real {@link TempDir}
 * reports directory. No Docker, no Spring, no database.
 */
class BenchRunExecutorTest {

    private static final String RUN_ID = "run-20261028-001";

    @TempDir
    Path reportsRoot;

    private final BenchExecProperties properties = new BenchExecProperties();
    private final FakeGateway gateway = new FakeGateway();
    private BenchRunExecutor executor;

    @BeforeEach
    void setUp() {
        properties.setReportsMount(reportsRoot.toString());
        properties.setReportMaxBytes(100);
        properties.setLogsMaxBytes(64);
        properties.setLogsMaxTail(50);
        properties.setStopGraceSeconds(7);
        gateway.snapshot = K6ContainerGateway.Snapshot.absent("jl-k6-" + RUN_ID);
        executor = new BenchRunExecutor(properties, gateway);
    }

    // -------------------------------------------------------------- start

    @Test
    void startCreatesTheReportDirectoryThenCreatesAndStartsThePlan() {
        BenchLaunchPlan plan = plan(RUN_ID);

        BenchExecutionState state = executor.start(plan);

        assertTrue(Files.isDirectory(reportsRoot.resolve(RUN_ID)));
        assertEquals(1, gateway.ensureCalls);
        assertEquals(plan, gateway.created);
        assertEquals(1, gateway.createCalls);
        assertEquals("RUNNING", state.status());
        assertEquals("cid-" + RUN_ID, state.containerId());
        assertEquals("via-nginx", state.trafficMode());
        assertEquals("nginx", state.targetInstance());
    }

    @Test
    void missingImageIsRefusedBeforeAnyDirectoryOrContainer() {
        gateway.ensureFailure = new BenchUnavailableException(
                "k6 image 'grafana/k6:1.3.0' is not present locally; run `docker pull ...` first");

        BenchUnavailableException ex = assertThrows(BenchUnavailableException.class,
                () -> executor.start(plan(RUN_ID)));

        assertTrue(ex.getMessage().contains("docker pull"), ex.getMessage());
        assertFalse(Files.exists(reportsRoot.resolve(RUN_ID)));
        assertEquals(0, gateway.createCalls);
    }

    @Test
    void aLiveSameNameContainerIsAConflictAndIsNotReplaced() {
        gateway.snapshot = new K6ContainerGateway.Snapshot("RUNNING", "jl-k6-" + RUN_ID, "cid",
                null, "2026-10-28T10:00:00Z", null, "grafana/k6:1.3.0", "smoke");

        assertThrows(BenchRunConflictException.class, () -> executor.start(plan(RUN_ID)));

        assertEquals(0, gateway.removeCalls);
        assertEquals(0, gateway.createCalls);
    }

    @Test
    void anExitedSameNameContainerIsRemovedAndRecreated() {
        gateway.snapshot = new K6ContainerGateway.Snapshot("EXITED", "jl-k6-" + RUN_ID, "old-cid",
                0, "2026-10-28T10:00:00Z", "2026-10-28T10:05:00Z", "grafana/k6:1.3.0", "smoke");

        executor.start(plan(RUN_ID));

        assertEquals(1, gateway.removeCalls);
        assertEquals("jl-k6-" + RUN_ID, gateway.removedName);
        assertEquals(1, gateway.createCalls);
    }

    // -------------------------------------------------------------- state

    @Test
    void stateMapsDockerFieldsAndReportSize() throws Exception {
        gateway.snapshot = new K6ContainerGateway.Snapshot("EXITED", "jl-k6-" + RUN_ID, "cid",
                0, "2026-10-28T10:00:00Z", "2026-10-28T10:05:00Z", "grafana/k6:1.3.0", "smoke");
        writeReport(RUN_ID, "{\"metrics\":{}}");

        BenchExecutionState state = executor.state(RUN_ID);

        assertEquals("EXITED", state.status());
        assertEquals("jl-k6-" + RUN_ID, state.containerName());
        assertEquals("cid", state.containerId());
        assertEquals("smoke", state.scenario());
        assertEquals(0, state.exitCode());
        assertEquals("2026-10-28T10:00:00Z", state.startedAt());
        assertEquals("2026-10-28T10:05:00Z", state.finishedAt());
        assertTrue(state.reportAvailable());
        assertEquals("{\"metrics\":{}}".getBytes(StandardCharsets.UTF_8).length,
                state.reportBytes());
    }

    @Test
    void anAbsentRunIsANormalStateWithAHint() {
        BenchExecutionState state = executor.state(RUN_ID);

        assertEquals("ABSENT", state.status());
        assertFalse(state.reportAvailable());
        assertEquals(0L, state.reportBytes());
        assertNotNull(state.note());
        assertTrue(state.note().contains("no k6 container"), state.note());
        // ABSENT has no container labels to read.
        assertNull(state.trafficMode());
        assertNull(state.targetInstance());
    }

    @Test
    void stateCarriesTheTrafficAttributionReadFromContainerLabels() {
        gateway.snapshot = new K6ContainerGateway.Snapshot("EXITED", "jl-k6-" + RUN_ID, "cid",
                0, "2026-10-28T10:00:00Z", "2026-10-28T10:05:00Z", "grafana/k6:1.3.0", "load",
                "target-instance", "app-2");

        BenchExecutionState state = executor.state(RUN_ID);

        assertEquals("target-instance", state.trafficMode());
        assertEquals("app-2", state.targetInstance());
        assertEquals("load", state.scenario());
    }

    @Test
    void aRunningRunWithoutReportExplainsWhenItAppears() {
        gateway.snapshot = new K6ContainerGateway.Snapshot("RUNNING", "jl-k6-" + RUN_ID, "cid",
                null, "2026-10-28T10:00:00Z", null, "grafana/k6:1.3.0", "smoke");

        BenchExecutionState state = executor.state(RUN_ID);

        assertFalse(state.reportAvailable());
        assertTrue(state.note().contains("when k6 exits"), state.note());
    }

    // -------------------------------------------------------------- logs

    @Test
    void logsClampTailAndByteBudgetToTheConfiguredCaps() {
        gateway.snapshot = new K6ContainerGateway.Snapshot("RUNNING", "jl-k6-" + RUN_ID, "cid",
                null, "2026-10-28T10:00:00Z", null, "grafana/k6:1.3.0", "smoke");
        gateway.logsResult = new K6ContainerGateway.LogChunk("partial", true, 7, 900);

        K6ContainerGateway.LogChunk chunk = executor.logs(RUN_ID, 99_999, 99_999);

        assertEquals(50, gateway.lastTail);
        assertEquals(64, gateway.lastMaxBytes);
        assertTrue(chunk.truncated());
        assertEquals(900L, chunk.originalBytes());
    }

    @Test
    void logsUseTheDefaultsWhenTheQueryOmitsThem() {
        gateway.snapshot = new K6ContainerGateway.Snapshot("RUNNING", "jl-k6-" + RUN_ID, "cid",
                null, "2026-10-28T10:00:00Z", null, "grafana/k6:1.3.0", "smoke");

        executor.logs(RUN_ID, null, null);

        // The default tail is itself bounded by the configured cap.
        assertEquals(properties.getLogsMaxTail(), gateway.lastTail);
        assertEquals(properties.getLogsMaxBytes(), gateway.lastMaxBytes);
    }

    @Test
    void logsForAnAbsentRunAreNotFound() {
        assertThrows(ContainerNotFoundException.class, () -> executor.logs(RUN_ID, 10, 100));
        assertEquals(0, gateway.logCalls);
    }

    // ------------------------------------------------------------- report

    @Test
    void reportReturnsTheRawSummaryBytes() throws Exception {
        writeReport(RUN_ID, "{\"ok\":true}");

        BenchReportContent content = executor.report(RUN_ID);

        assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), content.bytes());
        assertEquals(RUN_ID + "-summary.json", content.fileName());
    }

    @Test
    void missingReportIsNotFound() {
        assertThrows(BenchReportNotFoundException.class, () -> executor.report(RUN_ID));
    }

    @Test
    void oversizedReportIsRefused() throws Exception {
        writeReport(RUN_ID, "x".repeat(101));

        assertThrows(BenchReportTooLargeException.class, () -> executor.report(RUN_ID));
    }

    @Test
    void aSummaryFileThatEscapesTheRunDirectoryIsRefused() {
        properties.setSummaryFile("../escape.json");

        assertThrows(BenchUnavailableException.class, () -> executor.report(RUN_ID));
    }

    // ------------------------------------------------------------- run ids

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "a/b", "a\\b", "..", ".hidden", ""})
    void unsafeRunIdsAreRefusedBeforeAnyDockerOrFileCall(String runId) {
        assertThrows(BenchLaunchRejectedException.class, () -> executor.state(runId));
        assertThrows(BenchLaunchRejectedException.class, () -> executor.logs(runId, 10, 10));
        assertThrows(BenchLaunchRejectedException.class, () -> executor.report(runId));
        assertThrows(BenchLaunchRejectedException.class, () -> executor.stop(runId));
        assertThrows(BenchLaunchRejectedException.class, () -> executor.cleanup(runId));
        assertEquals(0, gateway.inspectCalls);
    }

    @Test
    void aHandCraftedPlanWithATraversalRunIdIsRefused() {
        BenchLaunchPlan plan = plan("../escape");

        assertThrows(BenchLaunchRejectedException.class, () -> executor.start(plan));
        assertEquals(0, gateway.ensureCalls);
        assertEquals(0, gateway.createCalls);
    }

    // ------------------------------------------------------------- stop/sweep

    @Test
    void stopIsIdempotentAndReportsWhetherItIssuedTheStop() {
        gateway.snapshot = new K6ContainerGateway.Snapshot("RUNNING", "jl-k6-" + RUN_ID, "cid",
                null, "2026-10-28T10:00:00Z", null, "grafana/k6:1.3.0", "smoke");
        gateway.stopResult = false;

        assertFalse(executor.stop(RUN_ID));
        assertEquals("jl-k6-" + RUN_ID, gateway.stoppedName);
        assertEquals(7, gateway.lastGraceSeconds);
    }

    @Test
    void stopOnAnAbsentContainerIsFalseWithoutCallingDocker() {
        assertFalse(executor.stop(RUN_ID));
        assertEquals(0, gateway.stopCalls);
    }

    @Test
    void cleanupStopsThenRemovesTheContainer() {
        gateway.snapshot = new K6ContainerGateway.Snapshot("EXITED", "jl-k6-" + RUN_ID, "cid",
                0, "2026-10-28T10:00:00Z", "2026-10-28T10:05:00Z", "grafana/k6:1.3.0", "smoke");

        assertTrue(executor.cleanup(RUN_ID));

        assertEquals(1, gateway.stopCalls);
        assertEquals(1, gateway.removeCalls);
    }

    @Test
    void sweepOrphansReturnsOnlyTheK6InventoryAndRemovesNothing() {
        gateway.k6Inventory = List.of(
                new K6ContainerGateway.ContainerSummary("jl-k6-old-1", "id-1", "old-1", "EXITED"),
                new K6ContainerGateway.ContainerSummary("jl-k6-old-2", "id-2", "old-2", "RUNNING"));

        List<K6ContainerGateway.ContainerSummary> orphans = executor.sweepOrphans();

        assertEquals(2, orphans.size());
        assertTrue(orphans.stream().allMatch(item -> item.containerName().startsWith("jl-k6-")));
        assertEquals(0, gateway.stopCalls);
        assertEquals(0, gateway.removeCalls);
    }

    // -------------------------------------------------------------- helpers

    private void writeReport(String runId, String content) throws Exception {
        Path dir = reportsRoot.resolve(runId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(properties.getSummaryFile()), content, StandardCharsets.UTF_8);
    }

    private static BenchLaunchPlan plan(String runId) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("RUN_ID", runId);
        env.put("BASE_URL", "http://nginx");
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("lab", "java-learn");
        labels.put("lab.role", "k6");
        labels.put("lab.run-id", runId);
        labels.put("lab.scenario", "smoke");
        labels.put(K6ContainerGateway.LABEL_TRAFFIC_MODE, "via-nginx");
        labels.put(K6ContainerGateway.LABEL_TARGET_INSTANCE, "nginx");
        String report = "/reports/" + runId + "/summary.json";
        return new BenchLaunchPlan(runId, "smoke", "jl-k6-" + runId, "grafana/k6:1.3.0",
                List.of("run", "--quiet", "--summary-export", report,
                        "/scripts/scenarios/smoke.js"),
                env,
                List.of(new BenchLaunchPlan.Bind("/host/k6", "/scripts", true),
                        new BenchLaunchPlan.Bind("jl-k6-reports", "/reports", false)),
                labels, "jl-net", report, report,
                new BenchLaunchPlan.Traffic("via-nginx", "nginx", "http://nginx", false, false,
                        "app-1:8080=1,app-2:8080=1,app-3:8080=1"));
    }

    /** In-memory gateway: records calls, never touches Docker. */
    private static final class FakeGateway implements K6ContainerGateway {

        K6ContainerGateway.Snapshot snapshot = Snapshot.absent("jl-k6-" + RUN_ID);
        BenchUnavailableException ensureFailure;
        boolean stopResult = true;
        boolean removeResult = true;
        K6ContainerGateway.LogChunk logsResult = new K6ContainerGateway.LogChunk("logs", false, 4, 4);
        List<K6ContainerGateway.ContainerSummary> k6Inventory = List.of();

        int ensureCalls;
        int createCalls;
        int inspectCalls;
        int logCalls;
        int stopCalls;
        int removeCalls;
        BenchLaunchPlan created;
        String removedName;
        String stoppedName;
        int lastTail = -1;
        int lastMaxBytes = -1;
        int lastGraceSeconds = -1;

        @Override
        public void ensureImage(String image) {
            ensureCalls++;
            if (ensureFailure != null) {
                throw ensureFailure;
            }
        }

        @Override
        public String createAndStart(BenchLaunchPlan plan) {
            createCalls++;
            created = plan;
            snapshot = new K6ContainerGateway.Snapshot("RUNNING", plan.containerName(),
                    "cid-" + plan.runId(), null, "2026-10-28T10:00:00Z", null, plan.image(),
                    plan.scenario(),
                    plan.labels().get(K6ContainerGateway.LABEL_TRAFFIC_MODE),
                    plan.labels().get(K6ContainerGateway.LABEL_TARGET_INSTANCE));
            return "cid-" + plan.runId();
        }

        @Override
        public Snapshot inspect(String runId) {
            inspectCalls++;
            return snapshot;
        }

        @Override
        public LogChunk logs(String containerName, int tail, int maxBytes) {
            logCalls++;
            lastTail = tail;
            lastMaxBytes = maxBytes;
            return logsResult;
        }

        @Override
        public boolean stop(String containerName, int graceSeconds) {
            stopCalls++;
            stoppedName = containerName;
            lastGraceSeconds = graceSeconds;
            return stopResult;
        }

        @Override
        public boolean remove(String containerName) {
            removeCalls++;
            removedName = containerName;
            return removeResult;
        }

        @Override
        public List<ContainerSummary> listK6Containers() {
            return new ArrayList<>(k6Inventory);
        }
    }
}
