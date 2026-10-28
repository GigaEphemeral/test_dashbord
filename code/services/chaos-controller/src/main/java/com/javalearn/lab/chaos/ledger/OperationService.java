package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.error.OperationCloseConflictException;
import com.javalearn.lab.chaos.error.OperationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationCloseRequest;
import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationPageView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationView;

/**
 * The B1-02 operation contract: every environment-changing request becomes one
 * {@code lab_operation} row and gets an {@code operationId} back.
 *
 * <p><b>Idempotency.</b> Same {@code Idempotency-Key} + same action + same
 * target inside {@code lab.ledger.idempotency.window-seconds} replays the
 * original row (no second side effect) and returns its {@code operationId}.
 * Outside the window the key is released (set NULL) and the request executes
 * again. The unique key in V2 is the race-proof backstop: a concurrent duplicate
 * insert loses on the index and is answered as a replay.</p>
 *
 * <p><b>Timeout is not failure.</b> The work runs on a dedicated daemon pool
 * (never the chaos TTL/revert scheduler). The caller waits at most
 * {@code lab.ledger.operation.sync-wait-ms}; after that the row moves to
 * PENDING_CONFIRMATION, the API answers 202 and the worker keeps going, turning
 * the row into APPLIED / RESTORED / FAILED when it finishes. A definite failure
 * inside the budget is rethrown to the caller, so the existing HTTP mapping
 * (404/403/409/503) is unchanged.</p>
 *
 * <p><b>Database outage.</b> Fault actions must still run while MySQL is down
 * (that is a first-class lab drill), so requests marked
 * {@link OperationRequest#strictLedger() non-strict} degrade to a stash write
 * plus an in-memory row instead of a 503; run lifecycle requests are strict
 * because the run row itself lives in MySQL.</p>
 */
public class OperationService {

    private static final Logger log = LoggerFactory.getLogger(OperationService.class);

    /**
     * B2-04a closure note cap. Deliberately a validation constant, not a
     * configuration key: the note is a short operator verdict, and a configurable
     * limit would have to be mirrored in application.yml / compose / .env.example
     * for no operational gain. The value matches the last_error column budget.
     */
    public static final int MAX_CLOSE_NOTE_LENGTH = 512;

    private final LedgerProperties properties;
    private final LedgerBootstrap bootstrap;
    private final RunLedgerRepository repository;
    private final LedgerWriter writer;
    private final ScheduledExecutorService executor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OperationService(LedgerProperties properties, LedgerBootstrap bootstrap,
                            RunLedgerRepository repository, LedgerWriter writer,
                            ScheduledExecutorService operationExecutor, ObjectMapper objectMapper,
                            Clock clock) {
        this.properties = properties;
        this.bootstrap = bootstrap;
        this.repository = repository;
        this.writer = writer;
        this.executor = operationExecutor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Creates (or replays) the operation and runs the work within the sync
     * budget.
     */
    public OperationExecution submit(OperationRequest request, OperationWork work) {
        return submit(request, work, properties.getOperation().getSyncWaitMs());
    }

    /**
     * Same contract without a wait budget. Used by {@code POST /chaos/stop-all}:
     * it is the teardown/recovery path, its callers (k6 teardown, console) need
     * the final counts, and "pending" there would hide whether the environment
     * was actually restored. The operation row and idempotency still apply.
     */
    public OperationExecution submitBlocking(OperationRequest request, OperationWork work) {
        return submit(request, work, -1L);
    }

    private OperationExecution submit(OperationRequest request, OperationWork work, long waitMs) {
        String key = IdempotencyKeys.normalize(request.idempotencyKey());
        boolean strict = request.strictLedger();
        if (strict) {
            LedgerReadiness.requireReady(properties, bootstrap);
        }

        Optional<OperationRecord> replay = lookupReplay(request, key, strict);
        if (replay.isPresent()) {
            return new OperationExecution(replay.get(), true, false, null);
        }

        OperationRecord operation = new OperationRecord(
                "op-" + UUID.randomUUID(), request.runId(), request.targetKind(),
                request.targetName(), AuditSanitizer.clip(request.action(), 64),
                OperationStatus.REQUESTED, request.ttlSeconds(), clock.instant(), null, null, null,
                properties.getOperator(), summarize(request.beforeSummary()), null);
        try {
            LedgerWriter.WriteResult written = writer.persistOperation(operation, key);
            if (written.stashed()) {
                if (strict) {
                    throw new LedgerUnavailableException("operation row could not be written ("
                            + written.error() + "); nothing was changed, retry when the ledger is back");
                }
                log.warn("Operation {} could not be written to the ledger; the action continues and the"
                        + " row is stashed for replay: {}", operation.operationId(), written.error());
            }
        } catch (DuplicateKeyException e) {
            // Another request won the same key while we were inserting.
            Optional<OperationRecord> winner = lookupByKey(request, key, strict);
            if (winner.isPresent()) {
                return new OperationExecution(winner.get(), true, false, null);
            }
            throw new LedgerUnavailableException("idempotency key " + key
                    + " collided but no operation row could be read back; retry");
        }

        Future<OperationOutcome> future;
        try {
            future = executor.submit(() -> execute(operation, work));
        } catch (RejectedExecutionException e) {
            settle(operation, OperationStatus.FAILED, null, null,
                    "operation executor rejected the task (shutting down)", null);
            throw new LedgerUnavailableException("operation executor is shutting down; retry");
        }

        long effectiveWait = waitMs < 0 ? Long.MAX_VALUE : waitMs;
        try {
            OperationOutcome outcome = future.get(effectiveWait, TimeUnit.MILLISECONDS);
            OperationStatus finalStatus = outcome.restored()
                    ? OperationStatus.RESTORED : OperationStatus.APPLIED;
            return new OperationExecution(reload(operation, finalStatus), false, false,
                    outcome.payload());
        } catch (TimeoutException e) {
            return pendingResult(operation, waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return pendingResult(operation, waitMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("operation " + operation.operationId() + " failed", cause);
        }
    }

    public OperationView view(String operationId) {
        LedgerReadiness.requireReady(properties, bootstrap);
        try {
            return view(require(operationId), false);
        } catch (DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "operation query");
        }
    }

    public OperationPageView page(String runId, String status, Integer limit, Integer offset) {
        LedgerReadiness.requireReady(properties, bootstrap);
        OperationStatus parsed = parseStatus(status);
        int effectiveLimit = limit == null ? properties.getQueryDefaultLimit() : limit;
        if (effectiveLimit < 1) {
            throw new InvalidLedgerRequestException("limit must be at least 1");
        }
        effectiveLimit = Math.min(effectiveLimit, properties.getQueryMaxLimit());
        int effectiveOffset = offset == null ? 0 : offset;
        if (effectiveOffset < 0) {
            throw new InvalidLedgerRequestException("offset must be at least 0");
        }
        String effectiveRun = runId == null || runId.isBlank() ? null : runId.trim();
        try {
            List<OperationView> items = repository
                    .listOperations(effectiveRun, parsed, effectiveLimit, effectiveOffset)
                    .stream().map(op -> view(op, false)).toList();
            long total = repository.countOperations(effectiveRun, parsed);
            return new OperationPageView(items, total, effectiveLimit, effectiveOffset);
        } catch (DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "operation query");
        }
    }

    /** Renders the current row of an execution (used by the controllers). */
    public OperationView view(OperationExecution execution) {
        if (execution.replay()) {
            return view(execution.operation(), true);
        }
        return view(execution.operation(), false);
    }

    /**
     * B2-04a: closes an operation restart recovery marked UNKNOWN.
     *
     * <p>Only UNKNOWN may be closed, and only as RESTORED or FAILED - the
     * operator looked at the target and records which side it ended on. The note
     * (optional, {@value #MAX_CLOSE_NOTE_LENGTH} chars max, no control
     * characters) is stored in {@code last_error} when present; when omitted the
     * recovery reason already in {@code last_error} is preserved. A RESTORED
     * verdict stamps {@code restored_at}; FAILED leaves the timestamps alone.
     * Idempotent per verdict: closing an operation that already has the requested
     * terminal state returns its view untouched (200); the other terminal verdict
     * is a conflict (409/40905), never a rewrite.</p>
     *
     * <p>This closes the operation verdict only. It does not touch the run row
     * and does not take or release the write-run environment lock - the lock is
     * released by the run stop/timeout path, so an UNKNOWN run keeps blocking a
     * new start until the operator closes that run as well.</p>
     */
    public OperationView closeUnknown(String operationId, OperationCloseRequest request) {
        LedgerReadiness.requireReady(properties, bootstrap);
        OperationStatus target = parseCloseStatus(request == null ? null : request.status());
        String note = closeNote(request == null ? null : request.note());
        try {
            OperationRecord current = require(operationId);
            if (current.status() == target) {
                // Repeated close with the same terminal verdict: no second write.
                return view(current, false);
            }
            if (current.status() != OperationStatus.UNKNOWN) {
                throw new OperationCloseConflictException("operation " + operationId + " is "
                        + current.status() + "; only UNKNOWN operations can be closed as "
                        + target + " (see GET /lab/ledger/recovery)");
            }
            int updated = repository.closeUnknownOperation(operationId, target, note,
                    target == OperationStatus.RESTORED ? clock.instant() : null);
            OperationRecord reloaded = repository.findOperation(operationId)
                    .orElseThrow(() -> new OperationNotFoundException(
                            "operation not found: " + operationId));
            if (updated == 0 && reloaded.status() != target) {
                // Lost a concurrent close with the other verdict; that verdict wins.
                throw new OperationCloseConflictException("operation " + operationId
                        + " was closed as " + reloaded.status() + " concurrently; requested "
                        + target);
            }
            log.info("Closed UNKNOWN operation {} as {}{}", operationId, target,
                    note == null ? "" : " (note " + note.length() + " chars)");
            return view(reloaded, false);
        } catch (DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "operation close");
        }
    }

    private static OperationStatus parseCloseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new InvalidLedgerRequestException(
                    "status is required; only RESTORED or FAILED may close an UNKNOWN operation");
        }
        OperationStatus parsed;
        try {
            parsed = OperationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidLedgerRequestException("unknown operation status '" + status
                    + "'; only RESTORED or FAILED may close an UNKNOWN operation");
        }
        if (parsed != OperationStatus.RESTORED && parsed != OperationStatus.FAILED) {
            throw new InvalidLedgerRequestException("status must be RESTORED or FAILED (was '"
                    + status + "'); REQUESTED/APPLIED/PENDING_CONFIRMATION/UNKNOWN are not close verdicts");
        }
        return parsed;
    }

    private static String closeNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        if (note.length() > MAX_CLOSE_NOTE_LENGTH) {
            throw new InvalidLedgerRequestException("note must be at most "
                    + MAX_CLOSE_NOTE_LENGTH + " characters (was " + note.length() + ")");
        }
        if (note.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidLedgerRequestException(
                    "note must not contain control characters (newlines/tabs included)");
        }
        return note;
    }

    private OperationExecution pendingResult(OperationRecord operation, long waitMs) {
        String note = "no confirmation within " + waitMs
                + "ms (lab.ledger.operation.sync-wait-ms); the worker keeps running and will settle"
                + " APPLIED/RESTORED/FAILED - poll GET /lab/operations/" + operation.operationId();
        OperationRecord current = operation;
        try {
            int moved = repository.markOperationPending(operation.operationId(), note);
            // Always reload: the worker may have settled between the timeout and
            // the update, in which case markOperationPending changed nothing.
            current = repository.findOperation(operation.operationId()).orElse(operation);
            if (moved == 0) {
                return new OperationExecution(current, false,
                        current.status() == OperationStatus.PENDING_CONFIRMATION, null);
            }
        } catch (DataAccessException e) {
            // The worker keeps running; answer with what we know instead of a
            // deceptive 503 that would suggest nothing happened.
            log.warn("Could not persist PENDING_CONFIRMATION for {} ({}); the worker continues",
                    operation.operationId(), e.getMostSpecificCause().getMessage());
            current = new OperationRecord(operation.operationId(), operation.runId(),
                    operation.targetKind(), operation.targetName(), operation.action(),
                    OperationStatus.PENDING_CONFIRMATION, operation.ttlSeconds(),
                    operation.requestedAt(), operation.appliedAt(), operation.restoredAt(), note,
                    operation.operator(), operation.beforeSummaryJson(), operation.afterSummaryJson());
        }
        return new OperationExecution(current, false,
                current.status() == OperationStatus.PENDING_CONFIRMATION, null);
    }

    private OperationOutcome execute(OperationRecord operation, OperationWork work) {
        return OperationContext.withOperation(operation.operationId(),
                () -> executeInner(operation, work));
    }

    private OperationOutcome executeInner(OperationRecord operation, OperationWork work) {
        try {
            OperationOutcome outcome = work.run();
            Instant finishedAt = clock.instant();
            OperationStatus status = outcome.restored() ? OperationStatus.RESTORED : OperationStatus.APPLIED;
            settle(operation, status, status == OperationStatus.APPLIED ? finishedAt : null,
                    status == OperationStatus.RESTORED ? finishedAt : null, null,
                    summarize(outcome.afterSummary()));
            return outcome;
        } catch (RuntimeException e) {
            String message = AuditSanitizer.describe(e);
            settle(operation, OperationStatus.FAILED, null, null, message, null);
            log.warn("Operation {} ({} {}) failed: {}", operation.operationId(),
                    operation.action(), operation.targetName(), message);
            throw e;
        }
    }

    /**
     * Settling never throws: the side effect already happened, so a lost
     * confirmation must not turn a successful start into an HTTP error. The
     * writer stashed the event and replay will settle the row.
     */
    private void settle(OperationRecord operation, OperationStatus status, Instant appliedAt,
                        Instant restoredAt, String lastError, String afterSummaryJson) {
        LedgerWriter.WriteResult written = writer.completeOperation(operation, status, appliedAt,
                restoredAt, lastError, afterSummaryJson);
        if (written.alreadySettled()) {
            log.debug("Operation {} was already settled (or recovered as UNKNOWN); keeping that verdict",
                    operation.operationId());
        } else if (written.stashed()) {
            log.warn("Operation {} settle event was stashed ({}); replay will apply it",
                    operation.operationId(), written.error());
        }
    }

    private Optional<OperationRecord> lookupReplay(OperationRequest request, String key, boolean strict) {
        if (key == null) {
            return Optional.empty();
        }
        try {
            Optional<OperationRecord> found = repository.findOperationByIdempotencyKey(key,
                    AuditSanitizer.clip(request.action(), 64), request.targetKind(), request.targetName());
            if (found.isEmpty()) {
                return Optional.empty();
            }
            OperationRecord operation = found.get();
            Instant windowEnd = operation.requestedAt()
                    .plusSeconds(properties.getIdempotency().getWindowSeconds());
            if (windowEnd.isAfter(clock.instant())) {
                log.info("Idempotent replay of {} for key {} (window ends {})",
                        operation.operationId(), key, windowEnd);
                return Optional.of(operation);
            }
            repository.releaseIdempotencyKey(operation.operationId(), key);
            log.info("Idempotency window of {} ended at {}; key {} is reusable",
                    operation.operationId(), windowEnd, key);
            return Optional.empty();
        } catch (DataAccessException e) {
            if (strict) {
                throw LedgerReadiness.unavailable(bootstrap, e, "idempotency lookup");
            }
            log.warn("Could not check idempotency key {} (ledger unavailable); executing anyway: {}",
                    key, e.getMostSpecificCause().getMessage());
            return Optional.empty();
        }
    }

    private Optional<OperationRecord> lookupByKey(OperationRequest request, String key, boolean strict) {
        try {
            return repository.findOperationByIdempotencyKey(key, AuditSanitizer.clip(request.action(), 64),
                    request.targetKind(), request.targetName());
        } catch (DataAccessException e) {
            if (strict) {
                throw LedgerReadiness.unavailable(bootstrap, e, "idempotency lookup");
            }
            return Optional.empty();
        }
    }

    private OperationRecord reload(OperationRecord operation, OperationStatus settledStatus) {
        try {
            return repository.findOperation(operation.operationId()).orElse(operation);
        } catch (DataAccessException e) {
            // The row was stashed (or MySQL died right after the settle): report
            // what this call actually did instead of failing a completed action.
            log.warn("Could not reload operation {} ({}); reporting the in-memory outcome",
                    operation.operationId(), e.getMostSpecificCause().getMessage());
            Instant at = clock.instant();
            return new OperationRecord(operation.operationId(), operation.runId(),
                    operation.targetKind(), operation.targetName(), operation.action(),
                    settledStatus, operation.ttlSeconds(), operation.requestedAt(),
                    settledStatus == OperationStatus.APPLIED ? at : null,
                    settledStatus == OperationStatus.RESTORED ? at : null, null,
                    operation.operator(), operation.beforeSummaryJson(), operation.afterSummaryJson());
        }
    }

    private OperationRecord require(String operationId) {
        try {
            return repository.findOperation(operationId)
                    .orElseThrow(() -> new OperationNotFoundException(
                            "operation not found: " + operationId));
        } catch (DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "operation query");
        }
    }

    OperationView view(OperationRecord operation, boolean replay) {
        return new OperationView(operation.operationId(), operation.runId(), operation.targetKind(),
                operation.targetName(), operation.action(), operation.status().name(),
                operation.ttlSeconds(), text(operation.requestedAt()), text(operation.appliedAt()),
                text(operation.restoredAt()), operation.lastError(), operation.operator(),
                json(operation.beforeSummaryJson()), json(operation.afterSummaryJson()), replay);
    }

    private String summarize(Map<String, Object> summary) {
        Map<String, Object> clean = AuditSanitizer.scrubSummary(summary);
        if (clean.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(clean);
            return json.length() > properties.getMaxPayloadBytes()
                    ? null : json;
        } catch (JsonProcessingException e) {
            log.warn("Operation summary is not serializable; dropping it: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode json(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static OperationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OperationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidLedgerRequestException("unknown operation status '" + status
                    + "'; expected one of " + java.util.Arrays.toString(OperationStatus.values()));
        }
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
