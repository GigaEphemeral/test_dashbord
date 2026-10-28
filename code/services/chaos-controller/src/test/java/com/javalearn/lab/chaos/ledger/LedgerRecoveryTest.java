package com.javalearn.lab.chaos.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restart recovery against the real schema: unfinished runs become UNKNOWN with
 * a timeline step, terminal/draft rows are untouched, unconfirmed operations
 * become UNKNOWN, and a second pass changes nothing.
 */
class LedgerRecoveryTest {

    private static final Instant NOW = LedgerTestDatabase.NOW;

    private RunLedgerRepository repository;
    private LedgerRecovery recovery;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.clean();
        repository = LedgerTestDatabase.repository();
        recovery = new LedgerRecovery(repository, LedgerTestDatabase.clock());
    }

    @Test
    void unfinishedRunsBecomeUnknownWithOneRecoveryStep() {
        insertRun("run-20261028-101", RunStatus.RUNNING);
        insertRun("run-20261028-102", RunStatus.STOPPING);
        insertRun("run-20261028-103", RunStatus.VERIFYING);
        insertRun("run-20261028-104", RunStatus.COMPLETED);
        insertRun("run-20261028-105", RunStatus.DRAFT);

        LedgerRecovery.RecoveryReport report = recovery.recover();

        assertThat(report.runsMarkedUnknown()).hasSize(3);
        assertThat(status("run-20261028-101")).isEqualTo(RunStatus.UNKNOWN);
        assertThat(status("run-20261028-102")).isEqualTo(RunStatus.UNKNOWN);
        assertThat(status("run-20261028-103")).isEqualTo(RunStatus.UNKNOWN);
        assertThat(status("run-20261028-104")).isEqualTo(RunStatus.COMPLETED);
        assertThat(status("run-20261028-105")).isEqualTo(RunStatus.DRAFT);

        for (String runId : List.of("run-20261028-101", "run-20261028-102", "run-20261028-103")) {
            assertThat(repository.findSteps(runId))
                    .singleElement()
                    .satisfies(step -> {
                        assertThat(step.stepKey()).isEqualTo("recovery");
                        assertThat(step.toStatus()).isEqualTo(RunStatus.UNKNOWN);
                        assertThat(step.occurredAt()).isEqualTo(NOW);
                    });
        }
        assertThat(repository.findSteps("run-20261028-104")).isEmpty();
        assertThat(repository.findSteps("run-20261028-105")).isEmpty();
    }

    @Test
    void requestedAndPendingOperationsBecomeUnknownButSettledOnesDoNot() {
        insertRun("run-20261028-201", RunStatus.RUNNING);
        insertOperation("op-requested", "run-20261028-201", OperationStatus.REQUESTED);
        insertOperation("op-pending", "run-20261028-201", OperationStatus.PENDING_CONFIRMATION);
        insertOperation("op-applied", "run-20261028-201", OperationStatus.APPLIED);
        insertOperation("op-failed", "run-20261028-201", OperationStatus.FAILED);

        LedgerRecovery.RecoveryReport report = recovery.recover();

        assertThat(report.operationsMarkedUnknown()).isEqualTo(2);
        assertThat(operationStatus("op-requested")).isEqualTo(OperationStatus.UNKNOWN);
        assertThat(operationStatus("op-pending")).isEqualTo(OperationStatus.UNKNOWN);
        assertThat(operationStatus("op-applied")).isEqualTo(OperationStatus.APPLIED);
        assertThat(operationStatus("op-failed")).isEqualTo(OperationStatus.FAILED);
        assertThat(repository.findOperations("run-20261028-201").stream()
                .filter(op -> op.operationId().equals("op-requested"))
                .findFirst().orElseThrow().lastError())
                .contains("unconfirmed after controller restart");
    }

    @Test
    void recoveryIsIdempotent() {
        insertRun("run-20261028-301", RunStatus.RUNNING);
        insertOperation("op-1", "run-20261028-301", OperationStatus.REQUESTED);

        recovery.recover();
        LedgerRecovery.RecoveryReport second = recovery.recover();

        assertThat(second.runsMarkedUnknown()).isEmpty();
        assertThat(second.operationsMarkedUnknown()).isZero();
        assertThat(repository.findSteps("run-20261028-301")).hasSize(1);
        assertThat(repository.findStashAudits()).isEmpty();
    }

    private void insertRun(String runId, RunStatus status) {
        repository.insertRun(LedgerTestDatabase.run(runId, status, NOW));
    }

    private void insertOperation(String operationId, String runId, OperationStatus status) {
        repository.insertOperation(new OperationRecord(operationId, runId, "container", "jl-app-1",
                "kill", status, 60, NOW, null, null, null));
    }

    private RunStatus status(String runId) {
        return repository.findRun(runId).orElseThrow().status();
    }

    private OperationStatus operationStatus(String operationId) {
        return repository.findOperations("run-20261028-201").stream()
                .filter(op -> op.operationId().equals(operationId))
                .findFirst().orElseThrow().status();
    }
}
