package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.chaos.error.LedgerConflictException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run start/stop as operations (B1-02).
 *
 * <p><b>Start</b> takes the single-writer lock first, then advances the persisted
 * state machine DRAFT → VALIDATING → READY → RUNNING. A lock conflict is a
 * 409/40902 naming the holder; an illegal source state is a 409/40901. A failure
 * after the first transition closes the run as FAILED and releases the lock, so
 * a failed start never leaves a READY run squatting on the environment.</p>
 *
 * <p><b>Stop</b> is RUNNING → STOPPING → COMPLETED and releases the lock; the
 * response's after-summary says whether this call really deleted the lock or it
 * had already expired / been taken over. An UNKNOWN run (controller restart) is
 * closed as ABORTED and its lock released - that is the operator cleanup path,
 * not a claim that the environment was restored.</p>
 *
 * <p><b>Contract-first boundary (B1-02).</b> There is no real pre-check (B1-04)
 * and no k6 execution (B2-02); VALIDATING/READY are persisted checkpoints that
 * the hooks will fill in. Stop does not yet stop any container for the same
 * reason. Do not read these states as evidence that traffic started or stopped.</p>
 */
public class RunLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycleService.class);

    private final LedgerProperties properties;
    private final LedgerBootstrap bootstrap;
    private final RunLedgerRepository repository;
    private final LedgerWriter writer;
    private final EnvLockService envLock;
    private final OperationService operations;
    private final RunLifecycleHook hook;
    private final Clock clock;

    public RunLifecycleService(LedgerProperties properties, LedgerBootstrap bootstrap,
                               RunLedgerRepository repository, LedgerWriter writer,
                               EnvLockService envLock, OperationService operations,
                               RunLifecycleHook hook, Clock clock) {
        this.properties = properties;
        this.bootstrap = bootstrap;
        this.repository = repository;
        this.writer = writer;
        this.envLock = envLock;
        this.operations = operations;
        this.hook = hook == null ? RunLifecycleHook.none() : hook;
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
        requireTransition(current.status(), RunStatus.VALIDATING, "start");
        EnvLockService.LockHandle lock = envLock.acquire(runId);
        boolean mutated = false;
        try {
            transition(runId, current.status(), RunStatus.VALIDATING, "validate", null);
            mutated = true;
            hook.precheck(runId);
            transition(runId, RunStatus.VALIDATING, RunStatus.READY, "ready", null);
            hook.launch(runId);
            transition(runId, RunStatus.READY, RunStatus.RUNNING, "start", null);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("runStatus", RunStatus.RUNNING.name());
            after.put("lockKey", lock.lockKey());
            after.put("lockExpiresAt", lock.expiresAt().toString());
            return OperationOutcome.applied(after, null);
        } catch (RuntimeException e) {
            closeFailedStart(runId, mutated, e);
            throw e;
        }
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
        transition(runId, RunStatus.STOPPING, RunStatus.COMPLETED, "complete", null);
        EnvLockService.ReleaseReport release = envLock.release(runId);
        return OperationOutcome.applied(lockSummary(RunStatus.COMPLETED, release), null);
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
            if (mutated) {
                RunRecord now = repository.findRun(runId).orElse(null);
                if (now != null
                        && (now.status() == RunStatus.VALIDATING || now.status() == RunStatus.READY)) {
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
