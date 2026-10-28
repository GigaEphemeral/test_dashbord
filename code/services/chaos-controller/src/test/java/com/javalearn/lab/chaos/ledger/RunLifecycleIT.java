package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.EnvLockConflictException;
import com.javalearn.lab.chaos.error.LedgerConflictException;
import com.javalearn.lab.chaos.error.PrecheckBlockedException;
import com.javalearn.lab.chaos.precheck.PrecheckItem;
import com.javalearn.lab.chaos.precheck.PrecheckReport;
import com.javalearn.lab.chaos.precheck.RunPrecheckGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Run start/stop against the real schema: the lock is taken after the B1-04
 * pre-check, DRAFT → VALIDATING → READY → RUNNING is persisted with one timeline
 * step per transition, stop reaches COMPLETED and frees the lock, a lock
 * conflict names the holder (40902), an illegal source state is a 409, a
 * failed launch leaves no READY run squatting on the environment, a slow launch
 * yields PENDING_CONFIRMATION and an UNKNOWN run can be closed as ABORTED.
 *
 * <p>The gate's own rules are covered by {@code RunPrecheckServiceTest}; here a
 * lambda stands in for the gate so this IT owns the ordering contract: a block
 * keeps the run DRAFT with no lock, warnings only reach the start summary.</p>
 */
class RunLifecycleIT {

    private static final Instant NOW = LedgerTestDatabase.NOW;
    private static final String RUN_A = "run-20261028-001";
    private static final String RUN_B = "run-20261028-002";

    @TempDir
    Path stashDir;

    private LedgerTestDatabase.MutableClock clock;
    private LedgerProperties properties;
    private RunLedgerRepository repository;
    private ScheduledExecutorService executor;
    private OperationService operations;
    private EnvLockService envLock;
    private RunLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.clean();
        clock = LedgerTestDatabase.mutableClock();
        properties = LedgerTestDatabase.properties(stashDir);
        repository = LedgerTestDatabase.repository();
        ObjectMapper objectMapper = new ObjectMapper();
        LedgerStash stash = new LedgerStash(properties, repository, objectMapper, clock);
        LedgerWriter writer = new LedgerWriter(repository, stash, objectMapper, clock);
        executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "test-run-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
        operations = new OperationService(properties, readyBootstrap(), repository, writer, executor,
                objectMapper, clock);
        envLock = new EnvLockService(properties, repository, clock);
        lifecycle = new RunLifecycleService(properties, readyBootstrap(), repository, writer, envLock,
                operations, passGate(), RunLifecycleHook.none(), clock);
        repository.insertRun(LedgerTestDatabase.run(RUN_A, RunStatus.DRAFT, NOW));
        repository.insertRun(LedgerTestDatabase.run(RUN_B, RunStatus.DRAFT, NOW));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void startTakesTheLockThenWalksDraftValidatingReadyRunning() {
        OperationExecution execution = lifecycle.start(RUN_A, null);

        assertThat(execution.pending()).isFalse();
        assertThat(execution.operation().status()).isEqualTo(OperationStatus.APPLIED);
        assertThat(execution.operation().action()).isEqualTo("start");
        assertThat(execution.operation().targetKind()).isEqualTo("run");
        assertThat(execution.operation().appliedAt()).isNotNull();
        assertThat(summary(execution.operation().beforeSummaryJson()).path("runStatus").asText())
                .isEqualTo("DRAFT");
        assertThat(summary(execution.operation().afterSummaryJson()).path("runStatus").asText())
                .isEqualTo("RUNNING");
        assertThat(summary(execution.operation().afterSummaryJson()).path("precheckVerdict").asText())
                .isEqualTo("PASS");

        assertThat(runStatus(RUN_A)).isEqualTo(RunStatus.RUNNING);
        assertThat(repository.findRun(RUN_A).orElseThrow().startedAt()).isNotNull();
        assertThat(repository.findSteps(RUN_A)).extracting(RunStepRecord::stepKey)
                .containsExactly("validate", "ready", "start");
        assertThat(repository.listEnvLocks()).singleElement().satisfies(lock -> {
            assertThat(lock.runId()).isEqualTo(RUN_A);
            assertThat(lock.expiresAt()).isAfter(NOW);
        });
    }

    @Test
    void theSecondRunIsRefusedWith40902AndTheHolderRunId() {
        lifecycle.start(RUN_A, null);

        assertThatThrownBy(() -> lifecycle.start(RUN_B, null))
                .isInstanceOf(EnvLockConflictException.class)
                .hasMessageContaining(RUN_A)
                .hasMessageContaining("POST /lab/runs/" + RUN_A + "/stop");

        assertThat(runStatus(RUN_A)).isEqualTo(RunStatus.RUNNING);
        assertThat(runStatus(RUN_B)).isEqualTo(RunStatus.DRAFT);
        assertThat(repository.listEnvLocks()).singleElement()
                .satisfies(lock -> assertThat(lock.runId()).isEqualTo(RUN_A));
        // The refused attempt is audited as a FAILED operation for run B.
        assertThat(repository.listOperations(RUN_B, OperationStatus.FAILED, 10, 0)).hasSize(1);
    }

    @Test
    void startOnARunningRunIsA409AndLeavesTheLockAlone() {
        lifecycle.start(RUN_A, null);

        assertThatThrownBy(() -> lifecycle.start(RUN_A, null))
                .isInstanceOf(LedgerConflictException.class)
                .hasMessageContaining("RUNNING");
        assertThat(repository.listEnvLocks()).singleElement()
                .satisfies(lock -> assertThat(lock.runId()).isEqualTo(RUN_A));
    }

    @Test
    void stopReachesCompletedAndReleasesTheLock() {
        lifecycle.start(RUN_A, null);

        OperationExecution stopped = lifecycle.stop(RUN_A, null);

        assertThat(stopped.operation().status()).isEqualTo(OperationStatus.APPLIED);
        var summary = summary(stopped.operation().afterSummaryJson());
        assertThat(summary.path("runStatus").asText()).isEqualTo("COMPLETED");
        assertThat(summary.path("lockReleased").asBoolean()).isTrue();
        assertThat(runStatus(RUN_A)).isEqualTo(RunStatus.COMPLETED);
        assertThat(repository.findRun(RUN_A).orElseThrow().endedAt()).isNotNull();
        assertThat(repository.findSteps(RUN_A)).extracting(RunStepRecord::stepKey)
                .containsExactly("validate", "ready", "start", "stop", "complete");
        assertThat(repository.listEnvLocks()).isEmpty();

        // The released lock lets the other run start.
        assertThat(lifecycle.start(RUN_B, null).operation().status()).isEqualTo(OperationStatus.APPLIED);
        assertThat(runStatus(RUN_B)).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void stopReportsThatItsLockWasTakenOverInsteadOfPretendingItWasReleased() {
        lifecycle.start(RUN_A, null);
        clock.advanceSeconds(properties.getEnvLock().getTtlSeconds() + 1);
        lifecycle.start(RUN_B, null);

        OperationExecution stopped = lifecycle.stop(RUN_A, null);

        assertThat(stopped.operation().status()).isEqualTo(OperationStatus.APPLIED);
        var summary = summary(stopped.operation().afterSummaryJson());
        assertThat(summary.path("lockReleased").asBoolean()).isFalse();
        assertThat(summary.path("lockHolderRunId").asText()).isEqualTo(RUN_B);
        assertThat(summary.path("lockReleaseNote").asText()).contains(RUN_B);
        assertThat(repository.listEnvLocks()).singleElement()
                .satisfies(lock -> assertThat(lock.runId()).isEqualTo(RUN_B));
    }

    @Test
    void anUnknownRunIsClosedAsAbortedAndItsLockReleased() {
        repository.insertRun(LedgerTestDatabase.run("run-20261028-009", RunStatus.RUNNING, NOW));
        envLock.acquire("run-20261028-009");
        new LedgerRecovery(repository, clock).recover();
        assertThat(runStatus("run-20261028-009")).isEqualTo(RunStatus.UNKNOWN);

        OperationExecution stopped = lifecycle.stop("run-20261028-009", null);

        assertThat(stopped.operation().status()).isEqualTo(OperationStatus.APPLIED);
        var summary = summary(stopped.operation().afterSummaryJson());
        assertThat(summary.path("runStatus").asText()).isEqualTo("ABORTED");
        assertThat(summary.path("unknownCleanup").asBoolean()).isTrue();
        assertThat(summary.path("lockReleased").asBoolean()).isTrue();
        assertThat(runStatus("run-20261028-009")).isEqualTo(RunStatus.ABORTED);
        assertThat(repository.listEnvLocks()).isEmpty();
    }

    @Test
    void aSlowLaunchHookYieldsPendingConfirmationAndThenRunning() {
        properties.getOperation().setSyncWaitMs(50);
        RunLifecycleService slow = new RunLifecycleService(properties, readyBootstrap(), repository,
                new LedgerWriter(repository, new LedgerStash(properties, repository, new ObjectMapper(),
                        clock), new ObjectMapper(), clock), envLock, operations, passGate(),
                new RunLifecycleHook() {
                    @Override
                    public void launch(String runId) {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }, clock);

        OperationExecution execution = slow.start(RUN_A, null);

        assertThat(execution.pending()).isTrue();
        assertThat(execution.operation().status()).isEqualTo(OperationStatus.PENDING_CONFIRMATION);

        OperationRecord settled = awaitOperation(execution.operation().operationId());
        assertThat(settled.status()).isEqualTo(OperationStatus.APPLIED);
        assertThat(runStatus(RUN_A)).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void aFailedLaunchClosesTheRunAsFailedAndReleasesTheLock() {
        RunLifecycleService failing = new RunLifecycleService(properties, readyBootstrap(), repository,
                new LedgerWriter(repository, new LedgerStash(properties, repository, new ObjectMapper(),
                        clock), new ObjectMapper(), clock), envLock, operations, passGate(),
                new RunLifecycleHook() {
                    @Override
                    public void launch(String runId) {
                        throw new IllegalStateException("k6 launcher is unavailable");
                    }
                }, clock);

        assertThatThrownBy(() -> failing.start(RUN_A, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("k6 launcher");

        assertThat(runStatus(RUN_A)).isEqualTo(RunStatus.FAILED);
        assertThat(repository.listEnvLocks()).isEmpty();
        OperationRecord failed = repository.listOperations(RUN_A, OperationStatus.FAILED, 10, 0).get(0);
        assertThat(failed.lastError()).contains("k6 launcher");
        // The environment is free for the next run.
        assertThat(envLock.acquire(RUN_B).runId()).isEqualTo(RUN_B);
    }

    @Test
    void stopOnADraftRunIsRejectedWith409AndAuditedAsFailed() {
        assertThatThrownBy(() -> lifecycle.stop(RUN_A, null))
                .isInstanceOf(LedgerConflictException.class)
                .hasMessageContaining("DRAFT");

        assertThat(runStatus(RUN_A)).isEqualTo(RunStatus.DRAFT);
        assertThat(repository.listOperations(RUN_A, OperationStatus.FAILED, 10, 0)).hasSize(1);
    }

    @Test
    void aBlockingPrecheckKeepsTheRunDraftTakesNoLockAndAuditsTheFailedStart() {
        RunPrecheckGate blocked = runId -> {
            throw new PrecheckBlockedException(PrecheckReport.of(runId, NOW.toString(),
                    List.of(PrecheckItem.block("traffic.stopped",
                            "background traffic jl-traffic is running",
                            Map.of("container", "jl-traffic"))), 10));
        };
        RunLifecycleService gated = new RunLifecycleService(properties, readyBootstrap(), repository,
                new LedgerWriter(repository, new LedgerStash(properties, repository, new ObjectMapper(),
                        clock), new ObjectMapper(), clock), envLock, operations, blocked,
                RunLifecycleHook.none(), clock);

        assertThatThrownBy(() -> gated.start(RUN_A, null))
                .isInstanceOf(PrecheckBlockedException.class)
                .hasMessageContaining("traffic.stopped");

        assertThat(runStatus(RUN_A)).isEqualTo(RunStatus.DRAFT);
        assertThat(repository.findSteps(RUN_A)).isEmpty();
        assertThat(repository.listEnvLocks()).isEmpty();
        OperationRecord failed = repository.listOperations(RUN_A, OperationStatus.FAILED, 10, 0).get(0);
        assertThat(failed.lastError()).contains("PrecheckBlockedException");
        assertThat(failed.lastError()).contains("traffic.stopped");

        // The environment is still free: fixing it unblocks the next attempt.
        assertThat(envLock.acquire(RUN_B).runId()).isEqualTo(RUN_B);
    }

    @Test
    void warningsDoNotBlockAndAreRecordedInTheStartSummary() {
        RunPrecheckGate warned = runId -> PrecheckReport.of(runId, NOW.toString(),
                List.of(PrecheckItem.warn("toxiproxy.clean",
                        "Toxiproxy is enabled but unreachable", Map.of())), 10);
        RunLifecycleService gated = new RunLifecycleService(properties, readyBootstrap(), repository,
                new LedgerWriter(repository, new LedgerStash(properties, repository, new ObjectMapper(),
                        clock), new ObjectMapper(), clock), envLock, operations, warned,
                RunLifecycleHook.none(), clock);

        OperationExecution execution = gated.start(RUN_A, null);

        assertThat(execution.operation().status()).isEqualTo(OperationStatus.APPLIED);
        assertThat(runStatus(RUN_A)).isEqualTo(RunStatus.RUNNING);
        var after = summary(execution.operation().afterSummaryJson());
        assertThat(after.path("precheckVerdict").asText()).isEqualTo("WARN");
        assertThat(after.path("precheckWarnings").asText()).contains("toxiproxy.clean");
    }

    @Test
    void startReplaysTheSameOperationForTheSameIdempotencyKey() {
        OperationExecution first = lifecycle.start(RUN_A, "start-key-1");
        OperationExecution second = lifecycle.start(RUN_A, "start-key-1");

        assertThat(second.replay()).isTrue();
        assertThat(second.operation().operationId()).isEqualTo(first.operation().operationId());
        assertThat(repository.countOperations(RUN_A, null)).isEqualTo(1);
        assertThat(runStatus(RUN_A)).isEqualTo(RunStatus.RUNNING);
    }

    private OperationRecord awaitOperation(String operationId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        OperationRecord last = null;
        while (Instant.now().isBefore(deadline)) {
            last = repository.findOperation(operationId).orElseThrow();
            if (last.status().isTerminal()) {
                return last;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("operation " + operationId + " stayed "
                + (last == null ? "?" : last.status()));
    }

    /** MySQL JSON columns come back pretty-printed, so parse instead of matching text. */
    private static com.fasterxml.jackson.databind.JsonNode summary(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("summary is not JSON: " + json, e);
        }
    }

    private RunStatus runStatus(String runId) {
        return repository.findRun(runId).orElseThrow().status();
    }

    /** The lifecycle contract is what this IT owns; the gate rules are tested separately. */
    private static RunPrecheckGate passGate() {
        return runId -> PrecheckReport.disabled(runId, NOW.toString());
    }

    private static LedgerBootstrap readyBootstrap() {
        LedgerBootstrap bootstrap = mock(LedgerBootstrap.class);
        when(bootstrap.isReady()).thenReturn(true);
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.OK, "2", null, NOW));
        return bootstrap;
    }
}
