package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.chaos.error.LedgerConflictException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.precheck.PrecheckReport;
import com.javalearn.lab.chaos.precheck.RunPrecheckGate;
import com.javalearn.lab.chaos.schedule.FaultScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run start/stop as operations (B1-02), gated by the B1-04 environment
 * pre-check.
 *
 * <p><b>Start order.</b> The run row is read and the source state is validated
 * (read-only), then the B1-04 pre-check runs. Only when it passes does the call
 * take the single-writer lock and advance the persisted state machine
 * DRAFT → VALIDATING → READY → RUNNING. A blocking pre-check finding throws
 * {@code PrecheckBlockedException} (409/40903) <em>before</em> any transition or
 * lock insert, so the run stays DRAFT and the environment is untouched - the
 * operator fixes it and retries. A lock conflict is a 409/40902 naming the
 * holder; an illegal source state is a 409/40901. A failure after the first
 * transition closes the run as FAILED and releases the lock, so a failed start
 * never leaves a READY run squatting on the environment.</p>
 *
 * <p><b>Warnings.</b> Non-blocking pre-check items are stored in the start
 * operation's after-summary ({@code precheckVerdict}, {@code precheckWarnings}),
 * which is where the console reads them from; the full report stays available
 * at {@code GET /lab/runs/{runId}/precheck}. The same report is also reachable
 * from the 409 body.</p>
 *
 * <p><b>Stop</b> is RUNNING → STOPPING → COMPLETED and releases the lock; the
 * response's after-summary says whether this call really deleted the lock or it
 * had already expired / been taken over. An UNKNOWN run (controller restart) is
 * closed as ABORTED and its lock released - that is the operator cleanup path,
 * not a claim that the environment was restored.</p>
 *
 * <p><b>B2-02b-2a.</b> The hook now returns a detail map: {@code launch}
 * details are merged into the start summary and {@code stop} details into the
 * stop summary. Stop is best-effort - a failing stop is recorded as
 * {@code k6StopError} and the run still reaches COMPLETED, because the operator
 * asked to close the run and the orphan sweep can remove a stranded
 * container. A failed start closes the run as FAILED and asks the hook to stop
 * the half-started k6 container before releasing the lock.</p>
 */
public class RunLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycleService.class);

    private final LedgerProperties properties;
    private final LedgerBootstrap bootstrap;
    private final RunLedgerRepository repository;
    private final LedgerWriter writer;
    private final EnvLockService envLock;
    private final OperationService operations;
    private final RunPrecheckGate precheck;
    private final RunLifecycleHook hook;
    private final FaultScheduler faultSchedule;
    private final Clock clock;

    public RunLifecycleService(LedgerProperties properties, LedgerBootstrap bootstrap,
                               RunLedgerRepository repository, LedgerWriter writer,
                               EnvLockService envLock, OperationService operations,
                               RunPrecheckGate precheck, RunLifecycleHook hook, Clock clock) {
        this(properties, bootstrap, repository, writer, envLock, operations, precheck, hook,
                FaultScheduler.NONE, clock);
    }

    /**
     * B3-00b canonical constructor: {@code faultSchedule} arms the persisted
     * run-scoped timeline after the k6 launch and cancels it on stop/failed
     * start. The nine-argument overload above keeps direct construction cheap
     * for tests and ledger-only tools.
     */
    public RunLifecycleService(LedgerProperties properties, LedgerBootstrap bootstrap,
                               RunLedgerRepository repository, LedgerWriter writer,
                               EnvLockService envLock, OperationService operations,
                               RunPrecheckGate precheck, RunLifecycleHook hook,
                               FaultScheduler faultSchedule, Clock clock) {
        this.properties = properties;
        this.bootstrap = bootstrap;
        this.repository = repository;
        this.writer = writer;
        this.envLock = envLock;
        this.operations = operations;
        this.precheck = precheck;
        this.hook = hook == null ? RunLifecycleHook.none() : hook;
        this.faultSchedule = faultSchedule == null ? FaultScheduler.NONE : faultSchedule;
        this.clock = clock;
    }

    public OperationExecution start(String runId, String idempotencyKey) {
        LedgerReadiness.requireReady(properties, bootstrap);
        RunRecord run = requireRun(runId);
        return operations.submit(OperationRequest.strict(run.runId(), "run", run.runId(), "start", null,
                idempotencyKey, Map.of("runStatus", run.status().name())), () -> doStart(runId));
    }

    public OperationExecution stop(String runId, String idempotencyKey) {
        LedgerReadiness.requireReady(properties, bootstrap);
        RunRecord run = requireRun(runId);
        return operations.submit(OperationRequest.strict(run.runId(), "run", run.runId(), "stop", null,
                idempotencyKey, Map.of("runStatus", run.status().name())), () -> doStop(runId));
    }

    private OperationOutcome doStart(String runId) {
        RunRecord current = requireRun(runId);
        // Read-only legality check first: a non-DRAFT run is a state conflict,
        // not a dirty environment.
        requireTransition(current.status(), RunStatus.VALIDATING, "start");
        // B1-04 gate: before any transition and before the write-run lock. A
        // blocking finding leaves the run DRAFT and the lock untouched.
        PrecheckReport report = precheck.enforce(runId);
        EnvLockService.LockHandle lock = envLock.acquire(runId);
        boolean mutated = false;
        try {
            transition(runId, current.status(), RunStatus.VALIDATING, "validate", null);
            mutated = true;
            transition(runId, RunStatus.VALIDATING, RunStatus.READY, "ready", null);
            RunLifecycleHook.LaunchResult launched = hook.launch(runId);
            // B3-00b: offsets are relative to the k6 launch. Arm before
            // READY→RUNNING so a failure follows the existing closeFailedStart
            // path (k6 stopped, run FAILED) instead of silently losing faults.
            int faultScheduleArmed = faultSchedule.arm(runId, clock.instant());
            transition(runId, RunStatus.READY, RunStatus.RUNNING, "start", null);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("runStatus", RunStatus.RUNNING.name());
            after.put("faultScheduleArmed", faultScheduleArmed);
            after.put("lockKey", lock.lockKey());
            after.put("lockExpiresAt", lock.expiresAt().toString());
            after.put("precheckVerdict", report.verdict());
            if (report.warningSummary() != null) {
                // Non-blocking findings live in the operation summary (the full
                // evidence stays at GET /lab/runs/{runId}/precheck).
                after.put("precheckWarnings", report.warningSummary());
            }
            if (launched != null) {
                after.putAll(launched.details());
            }
            return OperationOutcome.applied(after, null);
        } catch (RuntimeException e) {
            closeFailedStart(runId, mutated, e);
            throw e;
        }
    }

    /**
     * B3-00a: hard delete of a draft or closed run. Artifacts (k6 container,
     * report directory) are removed first and reported; a failed artifact
     * cleanup does not keep the ledger row alive, because the operator asked for
     * the row to disappear and the orphan sweep is the safety net.
     */
    public com.javalearn.lab.chaos.ledger.LedgerViews.RunDeleteView delete(String runId) {
        LedgerReadiness.requireReady(properties, bootstrap);
        RunRecord run = requireRun(runId);
        if (!(run.status().isTerminal() || run.status() == RunStatus.UNKNOWN
                || run.status() == RunStatus.DRAFT)) {
            throw new LedgerConflictException("run " + run.runId() + " is " + run.status()
                    + "; stop it (or close an UNKNOWN run) before deleting it. A live experiment "
                    + "cannot disappear from the ledger");
        }
        String artifacts;
        try {
            RunLifecycleHook.LaunchResult cleaned = hook.cleanupArtifacts(runId);
            artifacts = cleaned.details().isEmpty() ? "none" : cleaned.details().toString();
        } catch (RuntimeException e) {
            artifacts = "cleanup-failed: " + AuditSanitizer.describe(e);
            log.warn("Artifact cleanup before deleting run {} failed: {}", runId, e.toString());
        }
        boolean deleted;
        try {
            deleted = repository.deleteRun(runId) > 0;
        } catch (org.springframework.dao.DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "run delete");
        }
        return new com.javalearn.lab.chaos.ledger.LedgerViews.RunDeleteView(runId, deleted, artifacts);
    }

    private OperationOutcome doStop(String runId) {
        RunRecord current = requireRun(runId);
        if (current.status() == RunStatus.UNKNOWN) {
            transition(runId, RunStatus.UNKNOWN, RunStatus.ABORTED, "abort", null);
            EnvLockService.ReleaseReport release = envLock.release(runId);
            Map<String, Object> after = lockSummary(RunStatus.ABORTED, release);
            after.put("unknownCleanup", true);
            return OperationOutcome.applied(after, null);
        }
        requireTransition(current.status(), RunStatus.STOPPING, "stop");
        transition(runId, current.status(), RunStatus.STOPPING, "stop", null);
        // B3-00b: pending faults belong to the experiment; they must not fire
        // while/after k6 is stopped.
        cancelFaultSchedule(runId);
        Map<String, Object> after = new LinkedHashMap<>();
        try {
            RunLifecycleHook.LaunchResult stopped = hook.stop(runId);
            if (stopped != null) {
                after.putAll(stopped.details());
            }
        } catch (RuntimeException e) {
            // Best effort: the operator asked to close the run; a failed k6 stop
            // must not leave it RUNNING. The orphan sweep is the safety net.
            after.put("k6StopError", AuditSanitizer.describe(e));
            log.warn("Stopping the k6 container for run {} failed; completing the run anyway: {}",
                    runId, e.toString());
        }
        transition(runId, RunStatus.STOPPING, RunStatus.COMPLETED, "complete", null);
        EnvLockService.ReleaseReport release = envLock.release(runId);
        after.putAll(lockSummary(RunStatus.COMPLETED, release));
        return OperationOutcome.applied(after, null);
    }

    /**
     * Best effort: a start that failed after taking the lock must not leave a
     * READY run holding the environment, but it must never steal a lock from a
     * run that is actually running - and it must never abort a run that a
     * concurrent request for the same run already moved on.
     */
    private void closeFailedStart(String runId, boolean mutated, RuntimeException cause) {
        try {
            if (!mutated && cause instanceof LedgerConflictException) {
                // Another request for this run won the transition race; it owns
                // both the status and the (same-run) lock.
                log.warn("Start of run {} lost a transition race ({}); leaving the run and lock untouched",
                        runId, cause.getMessage());
                return;
            }
            // A failed start must not leave armed faults behind either.
            cancelFaultSchedule(runId);
            if (mutated) {
                RunRecord now = repository.findRun(runId).orElse(null);
                if (now != null
                        && (now.status() == RunStatus.VALIDATING || now.status() == RunStatus.READY)) {
                    // The hook may have created the k6 container before the
                    // transition to RUNNING failed; stop it before declaring the
                    // run FAILED (idempotent: ABSENT is fine).
                    stopK6BestEffort(runId);
                    boolean written = writer.transition(runId, now.status(), RunStatus.FAILED,
                            "start-failed", clock.instant(),
                            "{\"reason\":\"" + escape(AuditSanitizer.describe(cause)) + "\"}");
                    if (!written) {
                        log.warn("Could not persist FAILED for run {} after a failed start"
                                + " (ledger unavailable)", runId);
                    }
                }
            }
            RunRecord current = repository.findRun(runId).orElse(null);
            if (current != null && (current.status() == RunStatus.RUNNING || current.status().isUnfinished())) {
                log.warn("Keeping the write-run lock for run {}: it is still {}", runId, current.status());
                return;
            }
            EnvLockService.ReleaseReport release = envLock.release(runId);
            if (!release.released()) {
                log.warn("Failed start of run {} could not release the lock: {}", runId,
                        release.note());
            }
        } catch (RuntimeException suppressed) {
            log.warn("Cleanup after a failed start of run {} failed: {}", runId, suppressed.toString());
        }
    }

    private Map<String, Object> lockSummary(RunStatus status, EnvLockService.ReleaseReport release) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("runStatus", status.name());
        after.put("lockReleased", release.released());
        if (release.note() != null) {
            after.put("lockReleaseNote", release.note());
        }
        if (release.currentHolderRunId() != null) {
            after.put("lockHolderRunId", release.currentHolderRunId());
        }
        return after;
    }

    /** A failed start must not leave a half-launched k6 container behind. */
    private void stopK6BestEffort(String runId) {
        try {
            hook.stop(runId);
        } catch (RuntimeException e) {
            log.warn("Stopping the k6 container after a failed start of run {} failed: {}",
                    runId, e.toString());
        }
    }

    /** Cancel is best-effort by contract; a failure must not mask the real cause. */
    private void cancelFaultSchedule(String runId) {
        try {
            faultSchedule.cancel(runId);
        } catch (RuntimeException e) {
            log.warn("Cancelling the fault schedule of run {} failed: {}", runId, e.toString());
        }
    }

    private void transition(String runId, RunStatus from, RunStatus to, String stepKey, String detailJson) {
        boolean written = writer.transition(runId, from, to, stepKey, clock.instant(), detailJson);
        if (!written) {
            throw new LedgerUnavailableException("run " + runId + " transition " + from + "->" + to
                    + " could not be persisted (ledger unavailable); nothing is assumed applied");
        }
    }

    private void requireTransition(RunStatus from, RunStatus to, String operation) {
        if (!from.canTransitionTo(to)) {
            throw new LedgerConflictException("run is " + from + "; cannot " + operation
                    + " (needs a transition to " + to + "). Legal next states: "
                    + String.join(", ", from.allowedTargets().stream().map(Enum::name).sorted().toList()));
        }
    }

    private RunRecord requireRun(String runId) {
        try {
            return repository.findRun(runId)
                    .orElseThrow(() -> new LedgerRunNotFoundException("run not found: " + runId));
        } catch (org.springframework.dao.DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "run query");
        }
    }

    /**
     * Minimal JSON string escape. Control characters are flattened to spaces:
     * a raw newline would make the {@code lab_run_step.detail} JSON column
     * reject the row, and the detail is only read by humans.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replaceAll("[\\p{Cntrl}]", " ");
    }
}
