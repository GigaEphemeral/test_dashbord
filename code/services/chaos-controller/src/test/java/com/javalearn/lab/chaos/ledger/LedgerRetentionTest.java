package com.javalearn.lab.chaos.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention against the real cascade: keeping the newest N runs deletes older
 * runs and every child row (params/steps/env/operations/events/windows/checks)
 * with them, so no orphan is left in {@code lab_ledger}.
 */
class LedgerRetentionTest {

    @TempDir
    Path stashDir;

    private RunLedgerRepository repository;
    private LedgerProperties properties;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.clean();
        repository = LedgerTestDatabase.repository();
        properties = LedgerTestDatabase.properties(stashDir);
    }

    @Test
    void keepsTheNewestRunsAndCascadesChildrenOfTheEvictedOnes() {
        properties.setRetentionRuns(3);
        Instant base = LedgerTestDatabase.NOW.minusSeconds(100);
        for (int i = 1; i <= 5; i++) {
            String runId = String.format("run-20261028-%03d", i);
            Instant created = base.plusSeconds(i * 10L);
            repository.insertRun(LedgerTestDatabase.run(runId, RunStatus.COMPLETED, created));
            repository.insertParam(runId, "load", "{\"vus\":" + i + "}", created);
            repository.insertStep(new RunStepRecord(runId, "start", RunStatus.READY,
                    RunStatus.RUNNING, created, 0L, null));
        }

        int deleted = new LedgerRetention(properties, repository).prune();

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.countRuns(null, null)).isEqualTo(3);
        assertThat(repository.findRun("run-20261028-001")).isEmpty();
        assertThat(repository.findRun("run-20261028-002")).isEmpty();
        assertThat(repository.findRun("run-20261028-003")).isPresent();
        assertThat(repository.findRun("run-20261028-004")).isPresent();
        assertThat(repository.findRun("run-20261028-005")).isPresent();
        assertThat(repository.findParams("run-20261028-001")).isEmpty();
        assertThat(repository.findSteps("run-20261028-002")).isEmpty();
        assertThat(repository.findParams("run-20261028-005")).hasSize(1);
    }

    @Test
    void archivedRunsAreNeverPrunedAndDoNotConsumeTheKeepBudget() {
        properties.setRetentionRuns(2);
        Instant base = LedgerTestDatabase.NOW.minusSeconds(100);
        repository.insertRun(new RunRecord("run-20261028-001", "load", "保留的基线", null,
                RunStatus.COMPLETED, "abc", "local", base, base, base, base, true));
        for (int i = 2; i <= 4; i++) {
            String runId = String.format("run-20261028-%03d", i);
            repository.insertRun(LedgerTestDatabase.run(runId, RunStatus.COMPLETED,
                    base.plusSeconds(i * 10L)));
        }

        int deleted = new LedgerRetention(properties, repository).prune();

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findRun("run-20261028-001")).isPresent();
        assertThat(repository.findRun("run-20261028-002")).isEmpty();
        assertThat(repository.findRun("run-20261028-003")).isPresent();
        assertThat(repository.findRun("run-20261028-004")).isPresent();
    }

    @Test
    void pruningBelowTheCapChangesNothing() {
        properties.setRetentionRuns(10);
        repository.insertRun(LedgerTestDatabase.run("run-20261028-101", RunStatus.DRAFT,
                LedgerTestDatabase.NOW));

        assertThat(new LedgerRetention(properties, repository).prune()).isZero();
        assertThat(repository.countRuns(null, null)).isEqualTo(1);
    }
}
