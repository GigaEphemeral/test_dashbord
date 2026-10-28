package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * B2-04a: fault actions join the run timeline.
 *
 * <p>A fault request may name a {@code runId}. It is validated here (the run
 * must exist; it does <b>not</b> have to be RUNNING - an operator may inject a
 * fault before starting the run, and the B1-04 pre-check then blocks that
 * start). After the action really succeeded ({@code POST} answered 202 with an
 * action view, whether APPLIED or RESTORED), one {@code lab_fault_event} row is
 * written with {@code source=container}, {@code target=<container>} and
 * {@code event=<action>}; the detail snapshot carries
 * {@code ttlSeconds/status/operationId/expiresAt/runStatus}. Failed actions
 * write no "injected" event - the operation row already records FAILED and an
 * event claiming an injection would be a lie.</p>
 *
 * <p><b>The action wins over the audit.</b> A ledger write failure (MySQL down,
 * run deleted by retention between validation and write) is logged and
 * swallowed: the fault was already applied, so its HTTP result must not turn
 * into a failure because the timeline row was lost. This matches the non-strict
 * operation contract that keeps fault actions usable during a database drill.
 * Likewise an unreachable ledger during run-id validation logs a warning and
 * lets the fault run without a timeline row; only a reachable ledger that
 * answers "no such run" is a 404.</p>
 */
public class FaultEventService {

    private static final Logger log = LoggerFactory.getLogger(FaultEventService.class);

    /** {@code lab_run.run_id} is VARCHAR(64); reject impossible ids as 400. */
    static final int MAX_RUN_ID_LENGTH = 64;
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final RunLedgerRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FaultEventService(RunLedgerRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Validates the optional run id and returns the run status observed at
     * request time (recorded in the event detail).
     *
     * @return the current run status, or {@code null} when no run id was given
     *         or the ledger cannot answer (in which case the fault still runs)
     * @throws LedgerRunNotFoundException when the ledger is reachable and has no
     *                                    such run (HTTP 404)
     * @throws InvalidLedgerRequestException when the id cannot be a run id (400)
     */
    public String validateRun(String runId) {
        String normalized = normalize(runId);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > MAX_RUN_ID_LENGTH || !SAFE_RUN_ID.matcher(normalized).matches()
                || normalized.contains("..")) {
            throw new InvalidLedgerRequestException("runId must match "
                    + SAFE_RUN_ID.pattern() + " and be at most " + MAX_RUN_ID_LENGTH
                    + " characters (was '" + normalized + "')");
        }
        try {
            Optional<RunRecord> run = repository.findRun(normalized);
            if (run.isEmpty()) {
                throw new LedgerRunNotFoundException("run not found: " + normalized);
            }
            return run.get().status().name();
        } catch (DataAccessException e) {
            log.warn("Could not validate run {} for a fault action (ledger unavailable);"
                    + " the fault runs without a timeline row: {}", normalized,
                    e.getMostSpecificCause().getMessage());
            return null;
        }
    }

    /**
     * Writes one failure-timeline row. Never throws: see the class contract.
     *
     * @param runStatus status observed during {@link #validateRun(String)}; may
     *                  be {@code null} when validation could not reach the ledger
     * @return true when the row was written, false when it was skipped/failed
     */
    public boolean recordContainerAction(String runId, String operationId, String target,
                                         String action, Integer ttlSeconds, String status,
                                         Instant expiresAt, String runStatus) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ttlSeconds", ttlSeconds);
        detail.put("status", status);
        detail.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        return recordEvent(runId, operationId, "container", target, action, detail, runStatus);
    }

    /**
     * B2-04b2: generic timeline row for the Toxiproxy and thread-lab actions.
     *
     * <p>Same contract as {@link #recordContainerAction}: the row is written only
     * after the action really succeeded, and a write failure (MySQL down, run
     * deleted by retention between validation and write) is logged and swallowed
     * because the fault already happened. {@code operationId} and
     * {@code runStatus} are always added to the detail snapshot; keys in
     * {@code detail} never override them.</p>
     *
     * @param source   event source column ({@code container}, {@code toxiproxy},
     *                 {@code thread}); max 32 chars in the schema
     * @param target   proxy name, app instance or container name
     * @param event    event column ({@code kill}, {@code toxiproxy-add},
     *                 {@code thread-stop-all} ...); max 64 chars in the schema
     * @param detail   credential-free request summary; may be empty
     * @param runStatus status observed during validation, or {@code null}
     * @return true when the row was written, false when it was skipped/failed
     */
    public boolean recordEvent(String runId, String operationId, String source, String target,
                               String event, Map<String, Object> detail, String runStatus) {
        String normalized = normalize(runId);
        if (normalized == null) {
            return false;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (detail != null) {
            snapshot.putAll(detail);
        }
        snapshot.put("operationId", operationId);
        snapshot.put("runStatus", runStatus);
        String detailJson;
        try {
            detailJson = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Fault event detail for run {} is not serializable; skipping the row: {}",
                    normalized, e.getMessage());
            return false;
        }
        try {
            repository.insertFaultEvent(normalized, operationId, source, target, event,
                    clock.instant(), detailJson);
            return true;
        } catch (DataAccessException e) {
            log.warn("Fault event for run {} ({} {} on {}) could not be written;"
                            + " the action result is unchanged: {}", normalized, source, event,
                    target, e.getMostSpecificCause().getMessage());
            return false;
        }
    }

    private static String normalize(String runId) {
        if (runId == null) {
            return null;
        }
        String trimmed = runId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
