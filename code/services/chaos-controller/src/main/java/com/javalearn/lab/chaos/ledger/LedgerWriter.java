package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Write side of the ledger. A MySQL outage must not fail the chaos action that
 * was being recorded, so every {@link DataAccessException} is converted into a
 * stash event and the caller continues; a duplicate run id is the one exception
 * that is propagated, because it is a business-level race the service retries
 * with the next sequence number.
 *
 * <p>{@code lastWriteAt} / {@code lastError} feed
 * {@code GET /lab/ledger/status} so a stashed backlog is visible instead of
 * silently dropped.</p>
 */
public class LedgerWriter {

    private static final Logger log = LoggerFactory.getLogger(LedgerWriter.class);

    private final RunLedgerRepository repository;
    private final LedgerStash stash;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicReference<Instant> lastWriteAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public LedgerWriter(RunLedgerRepository repository, LedgerStash stash,
                        ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.stash = stash;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Inserts the run plus its parameter snapshots. Returns normally even when
     * the write was stashed; {@link DuplicateKeyException} is rethrown for the
     * run-id retry loop.
     */
    public void persistCreate(RunRecord run, String paramsJson) {
        persistCreate(run, Map.of("request", paramsJson));
    }

    /**
     * B3-00a: the draft can carry more than {@code request}. Every group is one
     * {@code lab_run_param} row; only {@code request} ever reaches the k6
     * planner. The stash body keeps the same shape so a replay re-creates all
     * groups.
     */
    public void persistCreate(RunRecord run, Map<String, String> groupsByGroup) {
        Map<String, String> groups = groupsByGroup == null || groupsByGroup.isEmpty()
                ? Map.of("request", "{}") : groupsByGroup;
        try {
            repository.insertRun(run);
            groups.forEach((group, payload) ->
                    repository.insertParam(run.runId(), group, payload, run.createdAt()));
            markWritten();
        } catch (DuplicateKeyException e) {
            throw e;
        } catch (DataAccessException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scenario", run.scenario());
            body.put("name", run.name());
            body.put("nodes", run.nodes());
            body.put("status", run.status().name());
            body.put("sourceCommit", run.sourceCommit());
            body.put("operator", run.operator());
            Map<String, JsonNode> stashGroups = new LinkedHashMap<>();
            groups.forEach((group, payload) -> stashGroups.put(group, readTree(payload)));
            // "params" keeps the legacy flat request payload (older stash files
            // and humans read it); "groups" is the complete replay source.
            body.put("params", readTree(groups.getOrDefault("request", "{}")));
            body.put("groups", stashGroups);
            stash.stash("RUN_CREATE", run.runId(), body, run.createdAt());
            markFailed(e);
        }
    }

    /**
     * Persists a validated state transition plus its timeline step. Returns true
     * when the ledger answered; false when the event was stashed. A lost
     * compare-and-set race is reported as a conflict, not stashed.
     */
    public boolean transition(String runId, RunStatus from, RunStatus to, String stepKey,
                              Instant occurredAt, String detailJson) {
        try {
            int updated = repository.updateRunStatus(runId, from, to, occurredAt,
                    to == RunStatus.RUNNING ? occurredAt : null,
                    to.isTerminal() ? occurredAt : null);
            if (updated == 0) {
                throw new com.javalearn.lab.chaos.error.LedgerConflictException(
                        "run " + runId + " is no longer in " + from + "; reload before transitioning");
            }
            repository.insertStep(new RunStepRecord(runId, stepKey, from, to, occurredAt, null, detailJson));
            markWritten();
            return true;
        } catch (DataAccessException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fromStatus", from.name());
            body.put("toStatus", to.name());
            body.put("stepKey", stepKey);
            body.put("occurredAt", occurredAt.toString());
            body.put("detail", detailJson);
            stash.stash("RUN_TRANSITION", runId, body, occurredAt);
            markFailed(e);
            return false;
        }
    }

    public Instant lastWriteAt() {
        return lastWriteAt.get();
    }

    public String lastError() {
        return lastError.get();
    }

    // ------------------------------------------------------- operations (B1-02)

    /**
     * Inserts one operation row. Like {@link #persistCreate} a duplicate key is
     * rethrown (the idempotency race is a business decision the caller resolves)
     * and any other database failure is stashed instead of failing the action.
     */
    public WriteResult persistOperation(OperationRecord operation, String idempotencyKey) {
        try {
            repository.insertOperation(operation, idempotencyKey);
            markWritten();
            return WriteResult.written(1);
        } catch (DuplicateKeyException e) {
            throw e;
        } catch (DataAccessException e) {
            boolean stashed = stash.stash("OPERATION", operation.runId(),
                    operationBody(operation, idempotencyKey), operation.requestedAt());
            markFailed(e);
            return WriteResult.stashed(stashed, rootMessage(e));
        }
    }

    /**
     * Settles an operation (only from REQUESTED / PENDING_CONFIRMATION). Returns
     * the number of updated rows, or a stashed marker when MySQL is down so the
     * caller can decide whether the lost confirmation matters.
     */
    public WriteResult completeOperation(OperationRecord operation, OperationStatus status,
                                         Instant appliedAt, Instant restoredAt, String lastError,
                                         String afterSummaryJson) {
        try {
            int updated = repository.completeOperation(operation.operationId(), status, appliedAt,
                    restoredAt, lastError, afterSummaryJson);
            markWritten();
            return WriteResult.written(updated);
        } catch (DataAccessException e) {
            Map<String, Object> body = operationBody(operation, null);
            body.put("status", status.name());
            body.put("appliedAt", appliedAt == null ? null : appliedAt.toString());
            body.put("restoredAt", restoredAt == null ? null : restoredAt.toString());
            body.put("lastError", lastError);
            body.put("afterSummary", afterSummaryJson);
            boolean stashed = stash.stash("OPERATION", operation.runId(), body, clock.instant());
            markFailed(e);
            return WriteResult.stashed(stashed, rootMessage(e));
        }
    }

    private Map<String, Object> operationBody(OperationRecord operation, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operation.operationId());
        body.put("runId", operation.runId());
        body.put("targetKind", operation.targetKind());
        body.put("targetName", operation.targetName());
        body.put("action", operation.action());
        body.put("status", operation.status().name());
        body.put("ttlSeconds", operation.ttlSeconds());
        body.put("requestedAt", operation.requestedAt().toString());
        body.put("appliedAt", operation.appliedAt() == null ? null : operation.appliedAt().toString());
        body.put("restoredAt", operation.restoredAt() == null ? null : operation.restoredAt().toString());
        body.put("lastError", operation.lastError());
        body.put("operator", operation.operator());
        body.put("beforeSummary", operation.beforeSummaryJson());
        body.put("afterSummary", operation.afterSummaryJson());
        body.put("idempotencyKey", idempotencyKey);
        return body;
    }

    /**
     * Outcome of one ledger write.
     *
     * @param updated rows the database changed (0 means the row had already
     *                moved on, e.g. recovery marked it UNKNOWN)
     * @param stashed true when MySQL was unreachable and the event went to disk
     */
    public record WriteResult(int updated, boolean stashed, String error) {

        static WriteResult written(int updated) {
            return new WriteResult(updated, false, null);
        }

        static WriteResult stashed(boolean stashed, String error) {
            return new WriteResult(-1, stashed, error);
        }

        public boolean alreadySettled() {
            return !stashed && updated == 0;
        }
    }

    private void markWritten() {
        lastWriteAt.set(clock.instant());
        lastError.set(null);
    }

    private void markFailed(DataAccessException e) {
        String message = rootMessage(e);
        lastError.set(message);
        log.warn("Ledger write failed and was stashed: {}", message);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialized run params are not valid JSON", e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return message.length() > 512 ? message.substring(0, 512) : message;
    }
}
