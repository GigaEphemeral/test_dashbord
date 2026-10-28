package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.chaos.error.EnvLockConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Single-writer lock against the real schema: exactly one holder, a conflict
 * that names the holder and the remedy, same-run re-entry, release, and TTL
 * takeover with an honest "this stop did not release it" report.
 */
class EnvLockIT {

    private static final Instant NOW = LedgerTestDatabase.NOW;

    @TempDir
    Path stashDir;

    private LedgerTestDatabase.MutableClock clock;
    private LedgerProperties properties;
    private RunLedgerRepository repository;
    private EnvLockService envLock;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.clean();
        clock = LedgerTestDatabase.mutableClock();
        properties = LedgerTestDatabase.properties(stashDir);
        repository = LedgerTestDatabase.repository();
        envLock = new EnvLockService(properties, repository, clock);
        repository.insertRun(LedgerTestDatabase.run("run-20261028-001", RunStatus.DRAFT, NOW));
        repository.insertRun(LedgerTestDatabase.run("run-20261028-002", RunStatus.DRAFT, NOW));
    }

    @Test
    void secondRunIsRefusedWithTheHolderAndTheReleasePath() {
        envLock.acquire("run-20261028-001");

        assertThatThrownBy(() -> envLock.acquire("run-20261028-002"))
                .isInstanceOf(EnvLockConflictException.class)
                .hasMessageContaining("run-20261028-001")
                .hasMessageContaining("POST /lab/runs/run-20261028-001/stop")
                .hasMessageContaining("wait for the TTL");
        assertThat(repository.listEnvLocks()).singleElement()
                .satisfies(lock -> assertThat(lock.runId()).isEqualTo("run-20261028-001"));
    }

    @Test
    void theSameRunReacquiresItsOwnLockWithoutExtendingTheLease() {
        EnvLockService.LockHandle first = envLock.acquire("run-20261028-001");
        clock.advanceSeconds(5);
        EnvLockService.LockHandle again = envLock.acquire("run-20261028-001");

        assertThat(again.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(repository.listEnvLocks()).hasSize(1);
    }

    @Test
    void releaseLetsAnotherRunAcquire() {
        envLock.acquire("run-20261028-001");

        EnvLockService.ReleaseReport report = envLock.release("run-20261028-001");
        assertThat(report.released()).isTrue();
        assertThat(report.note()).isNull();
        assertThat(repository.listEnvLocks()).isEmpty();

        assertThat(envLock.acquire("run-20261028-002").runId()).isEqualTo("run-20261028-002");
    }

    @Test
    void anExpiredLockIsTakenOverAndTheLateReleaseSaysSo() {
        properties.getEnvLock().setTtlSeconds(60);
        envLock.acquire("run-20261028-001");

        clock.advanceSeconds(61);
        EnvLockService.LockHandle taken = envLock.acquire("run-20261028-002");
        assertThat(taken.runId()).isEqualTo("run-20261028-002");

        EnvLockService.ReleaseReport late = envLock.release("run-20261028-001");
        assertThat(late.released()).isFalse();
        assertThat(late.currentHolderRunId()).isEqualTo("run-20261028-002");
        assertThat(late.note()).contains("run-20261028-002").contains("did not release");
        assertThat(repository.listEnvLocks()).singleElement()
                .satisfies(lock -> assertThat(lock.runId()).isEqualTo("run-20261028-002"));
    }

    @Test
    void releaseOfAMissingLockIsReportedInsteadOfAssumed() {
        EnvLockService.ReleaseReport report = envLock.release("run-20261028-001");

        assertThat(report.released()).isFalse();
        assertThat(report.note()).contains("already released");
    }
}
