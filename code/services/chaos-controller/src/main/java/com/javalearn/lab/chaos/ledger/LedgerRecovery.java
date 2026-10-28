package com.javalearn.lab.chaos.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Restart recovery (design §10.5): a controller that comes back cannot know
 * whether an unfinished run kept running or which side of an operation the
 * environment ended on. Nothing is assumed restored:
 *
 * <ul>
 *   <li>{@code RUNNING / STOPPING / VERIFYING} runs become {@code UNKNOWN} and
 *       get a {@code recovery} timeline step;</li>
 *   <li>{@code REQUESTED / PENDING_CONFIRMATION} operations become
 *       {@code UNKNOWN}.</li>
 * </ul>
 *
 * <p>Both updates are compare-and-set and only touch their source states, so
 * re-running recovery (an idempotency requirement: this runs once per startup)
 * writes no second step and changes no terminal row.</p>
 */
public class LedgerRecovery {

    private static final Logger log = LoggerFactory.getLogger(LedgerRecovery.class);
    private static final String STEP_KEY = "recovery";
    private static final String REASON_JSON = "{\"reason\":\"controller restart\"}";

    private final RunLedgerRepository repository;
    private final Clock clock;

    public LedgerRecovery(RunLedgerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public RecoveryReport recover() {
        Instant now = clock.instant();
        List<RunRecord> recovered = new ArrayList<>();
        for (RunRecord run : repository.findRunsByStatuses(
                EnumSet.of(RunStatus.RUNNING, RunStatus.STOPPING, RunStatus.VERIFYING))) {
            int updated = repository.markRunUnknown(run.runId(), run.status(), now);
            if (updated > 0) {
                repository.insertStep(new RunStepRecord(run.runId(), STEP_KEY, run.status(),
                        RunStatus.UNKNOWN, now, null, REASON_JSON));
                recovered.add(run);
            }
        }
        int operations = 0;
        for (OperationRecord operation : repository.findOperationsByStatuses(
                EnumSet.of(OperationStatus.REQUESTED, OperationStatus.PENDING_CONFIRMATION))) {
            operations += repository.markOperationUnknown(operation.operationId(),
                    "unconfirmed after controller restart; verify the target before assuming it was reverted");
        }
        if (!recovered.isEmpty() || operations > 0) {
            log.warn("Ledger recovery: {} unfinished run(s) marked UNKNOWN, {} unconfirmed operation(s) marked UNKNOWN",
                    recovered.size(), operations);
        }
        return new RecoveryReport(recovered, operations);
    }

    public record RecoveryReport(List<RunRecord> runsMarkedUnknown, int operationsMarkedUnknown) {
    }
}
