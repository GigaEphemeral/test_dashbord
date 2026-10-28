package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Operation contract against the real schema and a real daemon executor:
 * idempotent replay (same key = one side effect, one operationId), different
 * keys = two effects, an expired window frees the key, a slow worker answers
 * PENDING_CONFIRMATION and later settles APPLIED, a definite failure settles
 * FAILED with a scrubbed last_error, and a database outage degrades a non-strict
 * operation to the stash instead of blocking the action.
 */
class OperationServiceIT {

    private static final Instant NOW = LedgerTestDatabase.NOW;

    @TempDir
    Path stashDir;

    private LedgerTestDatabase.MutableClock clock;
    private LedgerProperties properties;
    private RunLedgerRepository repository;
    private ObjectMapper objectMapper;
    private ScheduledExecutorService executor;
    private OperationService operations;
    private LedgerStash stash;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.clean();
        clock = LedgerTestDatabase.mutableClock();
        properties = LedgerTestDatabase.properties(stashDir);
        repository = LedgerTestDatabase.repository();
        objectMapper = new ObjectMapper();
        stash = new LedgerStash(properties, repository, objectMapper, clock);
        LedgerWriter writer = new LedgerWriter(repository, stash, objectMapper, clock);
        executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "test-ledger-operation");
            thread.setDaemon(true);
            return thread;
        });
        operations = new OperationService(properties, readyBootstrap(), repository, writer, executor,
                objectMapper, clock);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void sameKeyReplaysTheOriginalOperationWithOneSideEffect() {
        AtomicInteger executions = new AtomicInteger();
        OperationRequest request = containerRequest("key-1", Map.of("container", "jl-app-1",
                "password", "hunter2"));

        OperationExecution first = operations.submit(request, counting(executions));
        OperationExecution second = operations.submit(request, counting(executions));

        assertThat(executions.get()).isEqualTo(1);
        assertThat(first.replay()).isFalse();
        assertThat(first.operation().status()).isEqualTo(OperationStatus.APPLIED);
        assertThat(second.replay()).isTrue();
        assertThat(second.operation().operationId()).isEqualTo(first.operation().operationId());
        assertThat(repository.countOperations(null, null)).isEqualTo(1);

        OperationRecord stored = repository.findOperation(first.operation().operationId()).orElseThrow();
        assertThat(stored.beforeSummaryJson()).doesNotContain("hunter2").doesNotContain("password");
        assertThat(stored.operator()).isEqualTo("local");
        assertThat(stored.appliedAt()).isNotNull();
        assertThat(stored.afterSummaryJson()).contains("scope");
    }

    @Test
    void differentKeysExecuteTwiceAndTheSameKeyOnAnotherActionDoesNotReplay() {
        AtomicInteger executions = new AtomicInteger();
        operations.submit(containerRequest("key-1", Map.of()), counting(executions));
        operations.submit(containerRequest("key-2", Map.of()), counting(executions));

        assertThat(executions.get()).isEqualTo(2);
        assertThat(repository.countOperations(null, null)).isEqualTo(2);

        // Same key, different action: a different contract, so it must run.
        OperationRequest freeze = new OperationRequest(null, "container", "jl-app-1", "freeze", 30,
                "key-1", Map.of(), false);
        OperationExecution third = operations.submit(freeze, counting(executions));
        assertThat(third.replay()).isFalse();
        assertThat(executions.get()).isEqualTo(3);
    }

    @Test
    void anExpiredIdempotencyWindowFreesTheKeyForReuse() {
        properties.getIdempotency().setWindowSeconds(1);
        AtomicInteger executions = new AtomicInteger();
        OperationRequest request = containerRequest("key-1", Map.of());

        OperationExecution first = operations.submit(request, counting(executions));
        clock.advanceSeconds(2);
        OperationExecution reused = operations.submit(request, counting(executions));

        assertThat(executions.get()).isEqualTo(2);
        assertThat(reused.replay()).isFalse();
        assertThat(reused.operation().operationId()).isNotEqualTo(first.operation().operationId());
        assertThat(repository.countOperations(null, null)).isEqualTo(2);
        // The old row keeps its audit but no longer owns the key.
        String key = LedgerTestDatabase.jdbc().queryForObject(
                "SELECT idempotency_key FROM lab_operation WHERE operation_id = ?",
                String.class, first.operation().operationId());
        assertThat(key).isNull();
    }

    @Test
    void slowWorkAnswersPendingConfirmationAndSettlesAppliedInTheBackground() {
        properties.getOperation().setSyncWaitMs(50);
        AtomicInteger executions = new AtomicInteger();

        OperationExecution execution = operations.submit(containerRequest("slow", Map.of()), () -> {
            executions.incrementAndGet();
            sleep(300);
            return OperationOutcome.applied(Map.of("scope", "slow"), null);
        });

        assertThat(execution.pending()).isTrue();
        assertThat(execution.operation().status()).isEqualTo(OperationStatus.PENDING_CONFIRMATION);
        assertThat(execution.operation().lastError()).contains("no confirmation within 50ms");

        OperationRecord settled = awaitSettled(execution.operation().operationId(),
                OperationStatus.APPLIED);
        assertThat(settled.appliedAt()).isNotNull();
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void failedWorkSettlesFailedWithACredentialFreeLastError() {
        assertThatThrownBy(() -> operations.submit(containerRequest("boom", Map.of()), () -> {
            throw new IllegalStateException("docker refused with password=hunter2 and Bearer abc.def");
        })).isInstanceOf(IllegalStateException.class);

        OperationRecord failed = repository.listOperations(null, OperationStatus.FAILED, 10, 0).get(0);
        assertThat(failed.lastError()).contains("IllegalStateException")
                .contains("password=***")
                .contains("Bearer ***")
                .doesNotContain("hunter2")
                .doesNotContain("abc.def");
    }

    @Test
    void nonStrictOperationRunsWhileTheLedgerIsDownAndStashesTheRow() {
        RunLedgerRepository broken = new RunLedgerRepository(
                new JdbcTemplate(brokenDataSource()));
        LedgerStash brokenStash = new LedgerStash(properties, broken, objectMapper, clock);
        LedgerWriter brokenWriter = new LedgerWriter(broken, brokenStash, objectMapper, clock);
        OperationService degraded = new OperationService(properties, readyBootstrap(), broken,
                brokenWriter, executor, objectMapper, clock);
        AtomicInteger executions = new AtomicInteger();

        OperationExecution execution = degraded.submit(containerRequest("offline", Map.of()),
                counting(executions));

        assertThat(executions.get()).isEqualTo(1);
        assertThat(execution.operation().status()).isEqualTo(OperationStatus.APPLIED);
        assertThat(brokenStash.pendingCount()).isGreaterThanOrEqualTo(1);
        // Strict callers are refused before any side effect.
        int strictExecutions = executions.get();
        assertThatThrownBy(() -> degraded.submit(new OperationRequest(null, "run", "run-x", "start",
                null, null, Map.of(), true), counting(executions)))
                .hasMessageContaining("operation row could not be written")
                .hasMessageContaining("retry when the ledger is back");
        assertThat(executions.get()).isEqualTo(strictExecutions);
    }

    @Test
    void pageFiltersByRunAndStatus() {
        operations.submit(containerRequest("a", Map.of()), () -> OperationOutcome.applied(Map.of(), null));
        assertThatThrownBy(() -> operations.submit(containerRequest("b", Map.of()), () -> {
            throw new IllegalStateException("nope");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(operations.page(null, "APPLIED", 10, 0).total()).isEqualTo(1);
        assertThat(operations.page(null, "FAILED", 10, 0).total()).isEqualTo(1);
        assertThat(operations.page(null, null, 10, 0).items()).hasSize(2);
        assertThat(operations.page(null, null, 10, 0).items())
                .extracting(LedgerViews.OperationView::status)
                .containsExactlyInAnyOrder("APPLIED", "FAILED");
    }

    @Test
    void closingUnknownIsCompareAndSetIdempotentAndKeepsTheRecoveryReasonWithoutANote() {
        repository.insertOperation(new OperationRecord("op-close-restored", null, "container",
                "jl-app-3", "kill", OperationStatus.UNKNOWN, 30, NOW, null, null,
                "unconfirmed after controller restart", "local", null, null));
        repository.insertOperation(new OperationRecord("op-close-failed", null, "container",
                "jl-app-4", "kill", OperationStatus.UNKNOWN, 30, NOW, null, null,
                "unconfirmed after controller restart", "local", null, null));

        var restored = operations.closeUnknown("op-close-restored",
                new LedgerViews.OperationCloseRequest("RESTORED", "target verified running"));
        assertThat(restored.status()).isEqualTo("RESTORED");
        assertThat(restored.lastError()).isEqualTo("target verified running");
        assertThat(restored.restoredAt()).isEqualTo(NOW.toString());

        // Same verdict again: no second write, the first note/timestamp stay.
        var repeated = operations.closeUnknown("op-close-restored",
                new LedgerViews.OperationCloseRequest("RESTORED", "a later note"));
        assertThat(repeated.lastError()).isEqualTo("target verified running");
        assertThat(repeated.restoredAt()).isEqualTo(NOW.toString());

        // The other verdict is a conflict, never a rewrite.
        assertThatThrownBy(() -> operations.closeUnknown("op-close-restored",
                new LedgerViews.OperationCloseRequest("FAILED", null)))
                .isInstanceOf(com.javalearn.lab.chaos.error.OperationCloseConflictException.class);

        // Omitted note: FAILED is written but the recovery reason is preserved
        // and no restored_at is invented.
        var failed = operations.closeUnknown("op-close-failed",
                new LedgerViews.OperationCloseRequest("FAILED", null));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.lastError()).isEqualTo("unconfirmed after controller restart");
        assertThat(failed.restoredAt()).isNull();
    }

    private OperationWork counting(AtomicInteger executions) {
        return () -> {
            executions.incrementAndGet();
            return OperationOutcome.applied(Map.of("scope", "test"), null);
        };
    }

    private static OperationRequest containerRequest(String key, Map<String, Object> before) {
        return new OperationRequest(null, "container", "jl-app-1", "kill", 30, key, before, false);
    }

    private OperationRecord awaitSettled(String operationId, OperationStatus expected) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        OperationRecord last = null;
        while (Instant.now().isBefore(deadline)) {
            last = repository.findOperation(operationId).orElseThrow();
            if (last.status() == expected) {
                return last;
            }
            sleep(25);
        }
        throw new AssertionError("operation " + operationId + " stayed " + (last == null ? "?" : last.status()));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static LedgerBootstrap readyBootstrap() {
        LedgerBootstrap bootstrap = mock(LedgerBootstrap.class);
        when(bootstrap.isReady()).thenReturn(true);
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.OK, "2", null, NOW));
        return bootstrap;
    }

    private static DataSource brokenDataSource() {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("ledger unavailable");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("ledger unavailable");
            }
        };
    }
}
