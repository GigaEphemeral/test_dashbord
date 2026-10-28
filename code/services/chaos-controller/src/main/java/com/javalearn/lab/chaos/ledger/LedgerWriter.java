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
     * Inserts the run plus its parameter snapshot. Returns normally even when
     * the write was stashed; {@link DuplicateKeyException} is rethrown for the
     * run-id retry loop.
     */
    public void persistCreate(RunRecord run, String paramsJson) {
        try {
            repository.insertRun(run);
            repository.insertParam(run.runId(), "request", paramsJson, run.createdAt());
            markWritten();
        } catch (DuplicateKeyException e) {
            throw e;
        } catch (DataAccessException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scenario", run.scenario());
            body.put("status", run.status().name());
            body.put("sourceCommit", run.sourceCommit());
            body.put("operator", run.operator());
            body.put("params", readTree(paramsJson));
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
