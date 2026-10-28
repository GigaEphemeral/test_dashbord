package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Local, file-backed write-behind buffer for ledger events.
 *
 * <p>The MySQL primary-kill drill deliberately hits the ledger write window, so a
 * write failure must never fail the chaos action. {@link LedgerWriter} hands the
 * intended event here; each event becomes one atomically written JSON file,
 * replayed in creation order as soon as MySQL is reachable again. Replay is
 * idempotent (an already-applied run create is skipped; a transition whose run
 * already sits in the target state is skipped), writes one
 * {@code lab_ledger_stash} audit row with {@code flushed_at} and only then
 * deletes the file. A failed replay keeps the file (and every later one,
 * preserving order) for the next attempt. The file cap drops the oldest event
 * with a WARN - the lab prefers bounded disk over an unbounded outage buffer.</p>
 *
 * <p>The stash directory is a compose named volume
 * ({@code jl-ledger-stash-data}), so a controller restart does not lose events.
 * Payloads are built by the caller from ledger rows only; credentials and full
 * cache values never reach this class.</p>
 */
public class LedgerStash {

    private static final Logger log = LoggerFactory.getLogger(LedgerStash.class);
    private static final String PREFIX = "stash-";
    private static final String SUFFIX = ".json";

    private final LedgerProperties properties;
    private final RunLedgerRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
    private final Path directory;

    public LedgerStash(LedgerProperties properties, RunLedgerRepository repository,
                       ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.directory = Paths.get(properties.getStashDir());
    }

    /**
     * Persists one event; never throws. Returns false when the event could not
     * be written (oversized or an I/O failure) - the caller still does not fail
     * the action.
     */
    public synchronized boolean stash(String kind, String runId, Map<String, Object> body, Instant createdAt) {
        try {
            Files.createDirectories(directory);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("kind", kind);
            event.put("runId", runId);
            event.put("createdAt", createdAt.toString());
            event.put("body", body);
            byte[] bytes = objectMapper.writeValueAsBytes(event);
            if (bytes.length > properties.getStashMaxBytes()) {
                log.warn("Ledger stash event {} for {} is {} bytes, above lab.ledger.stash-max-bytes={}; dropped",
                        kind, runId, bytes.length, properties.getStashMaxBytes());
                return false;
            }
            String name = String.format(Locale.ROOT, "%s%013d-%06d%s",
                    PREFIX, clock.millis(), sequence.incrementAndGet(), SUFFIX);
            Path target = directory.resolve(name);
            Path temporary = directory.resolve(name + ".tmp");
            Files.write(temporary, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            enforceLimit();
            log.warn("Ledger write for {} ({}) stashed at {}", runId, kind, target);
            return true;
        } catch (IOException e) {
            log.warn("Could not stash ledger event {} for {}: {}", kind, runId, e.getMessage());
            return false;
        }
    }

    /**
     * Replays files in creation order and stops at the first failure so a later
     * event never overtakes its predecessor. Returns the number of flushed
     * events.
     */
    public synchronized int replay() {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream
                    .filter(path -> path.getFileName().toString().startsWith(PREFIX)
                            && path.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list ledger stash directory {}: {}", directory, e.getMessage());
            return 0;
        }
        int flushed = 0;
        for (Path file : files) {
            if (!flush(file)) {
                break;
            }
            flushed++;
        }
        if (flushed > 0) {
            log.info("Replayed {} stashed ledger event(s)", flushed);
        }
        return flushed;
    }

    /** Number of events still waiting to be flushed. */
    public long pendingCount() {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(path -> path.getFileName().toString().startsWith(PREFIX)
                    && path.getFileName().toString().endsWith(SUFFIX)).count();
        } catch (IOException e) {
            log.warn("Could not count ledger stash files: {}", e.getMessage());
            return 0;
        }
    }

    private boolean flush(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("Could not read stashed ledger event {}: {}", file.getFileName(), e.getMessage());
            return false;
        }
        if (bytes.length > properties.getStashMaxBytes()) {
            log.warn("Stashed ledger event {} exceeds lab.ledger.stash-max-bytes; keeping it for inspection",
                    file.getFileName());
            return false;
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(bytes);
        } catch (IOException e) {
            quarantineCorrupt(file, e);
            return true;
        }
        String kind = text(event, "kind");
        // Operations outside a run carry a null run id, so this must not throw.
        String runId = nullableText(event, "runId");
        Instant createdAt;
        try {
            createdAt = Instant.parse(text(event, "createdAt"));
        } catch (RuntimeException e) {
            quarantineCorrupt(file, e);
            return true;
        }
        try {
            apply(kind, runId, event.path("body"), createdAt);
        } catch (RuntimeException e) {
            log.warn("Ledger stash replay failed for {} ({}): {}; keeping the file and later ones",
                    runId, kind, e.getMessage());
            return false;
        }
        try {
            repository.insertStashAudit(kind, objectMapper.writeValueAsString(event), createdAt, clock.instant());
            Files.delete(file);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Ledger stash event {} was applied but the audit/delete step failed: {}",
                    file.getFileName(), e.getMessage());
            return false;
        }
    }

    private void apply(String kind, String runId, JsonNode body, Instant createdAt) {
        switch (kind) {
            case "RUN_CREATE" -> applyRunCreate(runId, body, createdAt);
            case "RUN_TRANSITION" -> applyRunTransition(runId, body);
            case "OPERATION" -> applyOperation(runId, body);
            default -> throw new IllegalStateException("unknown stash event kind '" + kind + "'");
        }
    }

    private void applyRunCreate(String runId, JsonNode body, Instant createdAt) {
        if (repository.findRun(runId).isEmpty()) {
            RunRecord run = new RunRecord(
                    runId,
                    text(body, "scenario"),
                    RunStatus.valueOf(text(body, "status")),
                    nullableText(body, "sourceCommit"),
                    text(body, "operator"),
                    createdAt,
                    createdAt,
                    null,
                    null);
            repository.insertRun(run);
        }
        JsonNode params = body.path("params");
        // The run insert may have succeeded while the params insert failed; only
        // skip when a snapshot is already present (idempotent replay).
        if (!params.isMissingNode() && !params.isNull() && repository.findParams(runId).isEmpty()) {
            try {
                repository.insertParam(runId, "request", objectMapper.writeValueAsString(params), createdAt);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("could not serialize stashed params for " + runId, e);
            }
        }
    }

    private void applyRunTransition(String runId, JsonNode body) {
        RunStatus from = RunStatus.valueOf(text(body, "fromStatus"));
        RunStatus to = RunStatus.valueOf(text(body, "toStatus"));
        Instant occurredAt = Instant.parse(text(body, "occurredAt"));
        RunRecord run = repository.findRun(runId)
                .orElseThrow(() -> new IllegalStateException("run " + runId + " not found for transition"));
        if (run.status() == to) {
            return;
        }
        if (run.status() != from) {
            log.warn("Skipping stashed transition {}→{} for {}: run is {}",
                    from, to, runId, run.status());
            return;
        }
        int updated = repository.updateRunStatus(runId, from, to, occurredAt,
                to == RunStatus.RUNNING ? occurredAt : null,
                to.isTerminal() ? occurredAt : null);
        if (updated > 0) {
            repository.insertStep(new RunStepRecord(runId, text(body, "stepKey"), from, to, occurredAt,
                    nullableLong(body, "dwellMs"), nullableText(body, "detail")));
        }
    }

    /**
     * Inserts or settles one {@code lab_operation} row. Both create and settle
     * events share the same shape, so replay is order-independent in the one way
     * that matters: a missing row is inserted with the event's current status, an
     * existing row is settled only if it is still unconfirmed. The first verdict
     * wins (UNKNOWN from recovery is never overwritten).
     */
    private void applyOperation(String runId, JsonNode body) {
        String operationId = text(body, "operationId");
        OperationStatus status = OperationStatus.valueOf(text(body, "status"));
        Instant appliedAt = nullableInstant(body, "appliedAt");
        Instant restoredAt = nullableInstant(body, "restoredAt");
        String lastError = nullableText(body, "lastError");
        String afterSummary = nullableText(body, "afterSummary");
        var existing = repository.findOperation(operationId);
        if (existing.isEmpty()) {
            repository.insertOperation(new OperationRecord(
                    operationId,
                    runId,
                    text(body, "targetKind"),
                    text(body, "targetName"),
                    text(body, "action"),
                    status,
                    nullableInt(body, "ttlSeconds"),
                    Instant.parse(text(body, "requestedAt")),
                    appliedAt,
                    restoredAt,
                    lastError,
                    nullableText(body, "operator"),
                    nullableText(body, "beforeSummary"),
                    afterSummary), nullableText(body, "idempotencyKey"));
            return;
        }
        OperationStatus current = existing.get().status();
        if (current == status || current.isTerminal()) {
            return;
        }
        repository.completeOperation(operationId, status, appliedAt, restoredAt, lastError, afterSummary);
    }

    private static Instant nullableInstant(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null ? null : Instant.parse(value);
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private void quarantineCorrupt(Path file, Exception cause) {
        Path target = file.resolveSibling(file.getFileName() + ".corrupt");
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Moved corrupt ledger stash file {} to {}: {}", file.getFileName(), target.getFileName(),
                    cause.getMessage());
        } catch (IOException e) {
            log.warn("Corrupt ledger stash file {} could not be quarantined: {}", file.getFileName(),
                    e.getMessage());
        }
    }

    private void enforceLimit() throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(path -> path.getFileName().toString().startsWith(PREFIX)
                    && path.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
        }
        int excess = files.size() - properties.getStashMaxFiles();
        for (int i = 0; i < excess; i++) {
            Path oldest = files.get(i);
            Files.deleteIfExists(oldest);
            log.warn("Ledger stash exceeded lab.ledger.stash-max-files={}; dropped oldest {}",
                    properties.getStashMaxFiles(), oldest.getFileName());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("stash event is missing '" + field + "'");
        }
        return value.asText();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }
}
