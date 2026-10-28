package com.javalearn.lab.chaos.bench.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Requires;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Scenario;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Snapshot;
import com.javalearn.lab.chaos.error.LedgerConflictException;
import com.javalearn.lab.chaos.ledger.EnvLockService;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.LedgerWriter;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2-02b-2a watchdog tests: mocked ledger + executor, fixed clock. Direct
 * {@link BenchRunWatchdog#tick()} calls only; no scheduler thread, no Docker,
 * no Spring, no database.
 */
class BenchRunWatchdogTest {

    private static final String RUN_ID = "run-20261028-001";
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");

    private final BenchExecProperties properties = new BenchExecProperties();
    private final LedgerProperties ledgerProperties = new LedgerProperties();
    private final RunLedgerRepository repository = mock(RunLedgerRepository.class);
    private final LedgerWriter writer = mock(LedgerWriter.class);
    private final EnvLockService envLock = mock(EnvLockService.class);
    private final BenchRunExecutor executor = mock(BenchRunExecutor.class);
    private final BenchScenarioCatalog catalog = mock(BenchScenarioCatalog.class);
    private final LedgerBootstrap bootstrap = mock(LedgerBootstrap.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private BenchRunWatchdog watchdog;

    @BeforeEach
    void setUp() {
        properties.setTimeoutGraceSeconds(30);
        properties.setMaxDurationSeconds(3600);
        when(bootstrap.isReady()).thenReturn(true);
        when(envLock.release(anyString()))
                .thenReturn(new EnvLockService.ReleaseReport(true, null, null));
        when(repository.findRunsByStatuses(any())).thenReturn(List.of(run(NOW.minusSeconds(120))));
        when(repository.findParams(anyString())).thenReturn(List.of());
        when(catalog.load()).thenReturn(snapshot());
        watchdog = new BenchRunWatchdog(properties, ledgerProperties, bootstrap, repository,
                writer, envLock, executor, catalog, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // -------------------------------------------------------------- exit paths

    @Test
    void exitZeroWalksRunningStoppingCompletedAndReleasesTheLock() throws Exception {
        when(executor.state(RUN_ID)).thenReturn(exited(0));
        when(writer.transition(anyString(), any(), any(), anyString(), any(), anyString()))
                .thenReturn(true);

        watchdog.tick();

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(writer).transition(eq(RUN_ID), eq(RunStatus.RUNNING), eq(RunStatus.STOPPING),
                eq("k6-exit"), any(), detail.capture());
        verify(writer).transition(eq(RUN_ID), eq(RunStatus.STOPPING), eq(RunStatus.COMPLETED),
                eq("complete"), any(), anyString());
        verify(envLock).release(RUN_ID);
        JsonNode exitDetail = objectMapper.readTree(detail.getValue());
        assertThat(exitDetail.path("exitCode").asInt()).isEqualTo(0);
        assertThat(exitDetail.path("container").asText()).isEqualTo("jl-k6-" + RUN_ID);
    }

    @Test
    void aNonZeroExitFailsTheRunAndReleasesTheLockWithoutStopping() {
        when(executor.state(RUN_ID)).thenReturn(exited(2));
        when(writer.transition(anyString(), any(), any(), anyString(), any(), anyString()))
                .thenReturn(true);

        watchdog.tick();

        verify(writer).transition(eq(RUN_ID), eq(RunStatus.RUNNING), eq(RunStatus.FAILED),
                eq("k6-exit-failed"), any(), anyString());
        verify(writer, never()).transition(eq(RUN_ID), eq(RunStatus.RUNNING),
                eq(RunStatus.STOPPING), anyString(), any(), anyString());
        verify(envLock).release(RUN_ID);
        verify(executor, never()).stop(anyString());
        verify(executor, never()).cleanup(anyString());
    }

    @Test
    void anOverrunRunIsFailsStoppedCleanedAndItsLockReleased() {
        RunRecord startedLongAgo = run(NOW.minusSeconds(61));
        when(repository.findRunsByStatuses(any())).thenReturn(List.of(startedLongAgo));
        when(repository.findParams(RUN_ID)).thenReturn(List.of(
                new RunLedgerRepository.RunParamRow("request", "{\"SMOKE_DURATION\":\"30s\"}", NOW)));
        when(executor.state(RUN_ID)).thenReturn(running(NOW.minusSeconds(61)));
        when(executor.stop(RUN_ID)).thenReturn(true);
        when(executor.cleanup(RUN_ID)).thenReturn(true);
        when(writer.transition(anyString(), any(), any(), anyString(), any(), anyString()))
                .thenReturn(true);

        watchdog.tick();

        verify(writer).transition(eq(RUN_ID), eq(RunStatus.RUNNING), eq(RunStatus.FAILED),
                eq("k6-timeout"), any(), anyString());
        verify(executor).stop(RUN_ID);
        verify(executor).cleanup(RUN_ID);
        verify(envLock).release(RUN_ID);
    }

    @Test
    void aRunThatStillHasTimeIsLeftAlone() {
        when(executor.state(RUN_ID)).thenReturn(running(NOW.minusSeconds(10)));

        watchdog.tick();

        verify(executor, never()).stop(anyString());
        verify(writer, never()).transition(anyString(), any(), any(), anyString(), any(), anyString());
        verify(envLock, never()).release(anyString());
    }

    @Test
    void aLostTransitionRaceSkipsTheTickAndKeepsTheLock() {
        when(executor.state(RUN_ID)).thenReturn(exited(0));
        when(writer.transition(anyString(), any(), any(), anyString(), any(), anyString()))
                .thenThrow(new LedgerConflictException("run moved on"));

        watchdog.tick();

        verify(writer, times(1)).transition(anyString(), any(), any(), anyString(), any(),
                anyString());
        verify(envLock, never()).release(anyString());
        verify(executor, never()).stop(anyString());
    }

    @Test
    void aStashedTransitionKeepsTheLockAndTheNextTickRetries() {
        when(executor.state(RUN_ID)).thenReturn(exited(0));
        when(writer.transition(anyString(), any(), any(), anyString(), any(), anyString()))
                .thenReturn(false, true, true);

        watchdog.tick();
        verify(envLock, never()).release(anyString());

        watchdog.tick();
        verify(envLock).release(RUN_ID);
    }

    @Test
    void anAbsentContainerIsSkippedInsteadOfFailingTheRun() {
        when(executor.state(RUN_ID)).thenReturn(state("ABSENT", null, null, null));

        watchdog.tick();

        verify(writer, never()).transition(anyString(), any(), any(), anyString(), any(), anyString());
        verify(executor, never()).stop(anyString());
        verify(envLock, never()).release(anyString());
    }

    // ------------------------------------------------------------ orphan sweep

    @Test
    void orphanCleanupOnlyRemovesRunsThatAreNotRunningAndRunsOnce() {
        when(repository.findRunsByStatuses(any())).thenReturn(List.of());
        when(repository.findRun("run-live"))
                .thenReturn(Optional.of(record("run-live", RunStatus.RUNNING)));
        when(repository.findRun("run-dead"))
                .thenReturn(Optional.of(record("run-dead", RunStatus.FAILED)));
        when(repository.findRun("run-missing")).thenReturn(Optional.empty());
        when(executor.sweepOrphans()).thenReturn(List.of(
                summary("jl-k6-run-live", "run-live", "RUNNING"),
                summary("jl-k6-run-dead", "run-dead", "EXITED"),
                summary("jl-k6-run-missing", "run-missing", "RUNNING")));

        watchdog.tick();
        watchdog.tick();

        verify(executor, never()).cleanup("run-live");
        verify(executor, times(1)).cleanup("run-dead");
        verify(executor, times(1)).cleanup("run-missing");
        verify(executor, times(1)).sweepOrphans();
    }

    @Test
    void anUnreadyLedgerDefersTheOrphanSweepToTheNextTick() {
        when(bootstrap.isReady()).thenReturn(false);

        watchdog.tick();
        verify(repository, never()).findRunsByStatuses(any());
        verify(executor, never()).sweepOrphans();

        when(bootstrap.isReady()).thenReturn(true);
        when(repository.findRunsByStatuses(any())).thenReturn(List.of());
        when(executor.sweepOrphans()).thenReturn(List.of());
        watchdog.tick();

        verify(executor, times(1)).sweepOrphans();
    }

    // ---------------------------------------------------------- duration sum

    @Test
    void requestedDurationSumsLoadAndSpikeSegmentsAndFallsBackWhenUnknown() {
        Snapshot snapshot = snapshot();

        assertThat(BenchRunWatchdog.requestedDurationSeconds(snapshot, "load", Map.of(), 3600))
                .isEqualTo(600); // LOAD_DURATION is the total
        assertThat(BenchRunWatchdog.requestedDurationSeconds(snapshot, "spike", Map.of(), 3600))
                .isEqualTo(110); // baseline + ramp-up + duration + ramp-down
        assertThat(BenchRunWatchdog.requestedDurationSeconds(snapshot, "spike",
                Map.of("SPIKE_DURATION", "2m"), 3600)).isEqualTo(170);
        assertThat(BenchRunWatchdog.requestedDurationSeconds(snapshot, "smoke", Map.of(), 3600))
                .isEqualTo(30);
        assertThat(BenchRunWatchdog.requestedDurationSeconds(snapshot, "smoke",
                Map.of("SMOKE_DURATION", "45s"), 3600)).isEqualTo(45);
    }

    @Test
    void requestedDurationFallsBackForUnknownScenariosAndBadValues() {
        Snapshot snapshot = snapshot();

        assertThat(BenchRunWatchdog.requestedDurationSeconds(snapshot, "nope", Map.of(), 3600))
                .isEqualTo(3600);
        assertThat(BenchRunWatchdog.requestedDurationSeconds(null, "load", Map.of(), 7200))
                .isEqualTo(7200);
        assertThat(BenchRunWatchdog.requestedDurationSeconds(snapshot, "load",
                Map.of("LOAD_DURATION", "soon"), 3600)).isEqualTo(3600);
    }

    // -------------------------------------------------------------- fixtures

    private BenchExecutionState state(String status, Integer exitCode, String startedAt,
                                      String finishedAt) {
        return new BenchExecutionState(status, "jl-k6-" + RUN_ID, "cid-" + RUN_ID, "load",
                exitCode, startedAt, finishedAt, false, 0, null);
    }

    private BenchExecutionState exited(int exitCode) {
        return state("EXITED", exitCode, "2026-10-28T09:50:00Z", "2026-10-28T09:55:00Z");
    }

    private BenchExecutionState running(Instant startedAt) {
        return state("RUNNING", null, startedAt.toString(), null);
    }

    private static RunRecord run(Instant startedAt) {
        return new RunRecord(RUN_ID, "smoke", RunStatus.RUNNING, "sha", "local",
                startedAt, NOW, startedAt, null);
    }

    private static RunRecord record(String runId, RunStatus status) {
        return new RunRecord(runId, "smoke", status, "sha", "local", NOW, NOW, NOW, null);
    }

    private static K6ContainerGateway.ContainerSummary summary(String container, String runId,
                                                               String status) {
        return new K6ContainerGateway.ContainerSummary(container, "id-" + runId, runId, status);
    }

    private static Snapshot snapshot() {
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        scenarios.put("load", scenario(Map.of("LOAD_DURATION", "10m")));
        scenarios.put("spike", scenario(Map.of(
                "SPIKE_BASELINE_DURATION", "30s",
                "SPIKE_RAMP_UP", "10s",
                "SPIKE_DURATION", "60s",
                "SPIKE_RAMP_DOWN", "10s")));
        scenarios.put("smoke", scenario(Map.of("SMOKE_DURATION", "30s")));
        return new Snapshot("OK", 1, Map.of("ui", List.of("load", "spike", "smoke")), scenarios);
    }

    private static Scenario scenario(Map<String, Object> params) {
        return new Scenario("scenarios/load.js", false, new Requires(false, false, "dev"), true,
                true, List.of(), params, true);
    }
}
