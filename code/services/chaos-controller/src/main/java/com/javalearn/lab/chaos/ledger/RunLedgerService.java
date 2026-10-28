package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.error.LedgerConflictException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.javalearn.lab.chaos.ledger.LedgerViews.CheckView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.EnvView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.FaultEventView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.LedgerRecoveryView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.LedgerStatusView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.ParamView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateRequest;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunDetailView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunPageView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.StepView;

/**
 * Business entry point for the ledger: create/list/detail plus the ledger
 * status and restart-recovery views. It owns run-id generation
 * ({@code run-yyyyMMdd-NNN} from the injected UTC clock, retried on the unique
 * key) and the payload guard that keeps credentials out of JSON snapshots.
 * B1-02's idempotency keys, environment-lock flow, B1-03 aggregation and B2 k6
 * execution are deliberately out of scope here.
 */
public class RunLedgerService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);
    private static final Pattern SCENARIO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|credential|api[-_]?key).*");

    private final LedgerProperties properties;
    private final LedgerTargetResolver resolver;
    private final LedgerBootstrap bootstrap;
    private final RunLedgerRepository repository;
    private final LedgerWriter writer;
    private final LedgerStash stash;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RunLedgerService(LedgerProperties properties, LedgerTargetResolver resolver,
                            LedgerBootstrap bootstrap, RunLedgerRepository repository,
                            LedgerWriter writer, LedgerStash stash, ObjectMapper objectMapper,
                            Clock clock) {
        this.properties = properties;
        this.resolver = resolver;
        this.bootstrap = bootstrap;
        this.repository = repository;
        this.writer = writer;
        this.stash = stash;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public RunCreateView create(RunCreateRequest request) {
        requireReady();
        String scenario = validateScenario(request == null ? null : request.scenario());
        JsonNode params = objectMapper.valueToTree(
                request == null || request.params() == null ? Map.of() : request.params());
        rejectSensitiveKeys(params, "params");
        String paramsJson = serialize(params);
        if (paramsJson.getBytes(StandardCharsets.UTF_8).length > properties.getMaxPayloadBytes()) {
            throw new InvalidLedgerRequestException("run params exceed lab.ledger.max-payload-bytes="
                    + properties.getMaxPayloadBytes());
        }

        Instant now = clock.instant();
        DuplicateKeyException lastCollision = null;
        for (int attempt = 1; attempt <= properties.getRunIdMaxAttempts(); attempt++) {
            String runId = nextRunId(now);
            RunRecord run = new RunRecord(runId, scenario, RunStatus.DRAFT,
                    properties.getSourceCommit(), properties.getOperator(), now, now, null, null);
            try {
                writer.persistCreate(run, paramsJson);
                return new RunCreateView(run.runId(), run.status().name(), text(run.createdAt()));
            } catch (DuplicateKeyException e) {
                lastCollision = e;
            }
        }
        throw new LedgerConflictException("could not allocate a unique run id after "
                + properties.getRunIdMaxAttempts() + " attempts; last collision: "
                + (lastCollision == null ? "none" : lastCollision.getMostSpecificCause().getMessage()));
    }

    public RunPageView list(String scenario, String status, Integer limit, Integer offset) {
        requireReady();
        String effectiveScenario = scenario == null || scenario.isBlank() ? null : scenario.trim();
        RunStatus effectiveStatus = parseStatus(status);
        int effectiveLimit = limit == null ? properties.getQueryDefaultLimit() : limit;
        if (effectiveLimit < 1) {
            throw new InvalidLedgerRequestException("limit must be at least 1");
        }
        effectiveLimit = Math.min(effectiveLimit, properties.getQueryMaxLimit());
        int effectiveOffset = offset == null ? 0 : offset;
        if (effectiveOffset < 0) {
            throw new InvalidLedgerRequestException("offset must be at least 0");
        }
        try {
            List<RunView> items = repository
                    .listRuns(effectiveScenario, effectiveStatus, effectiveLimit, effectiveOffset)
                    .stream().map(this::view).toList();
            long total = repository.countRuns(effectiveScenario, effectiveStatus);
            return new RunPageView(items, total, effectiveLimit, effectiveOffset);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    public RunDetailView get(String runId) {
        requireReady();
        try {
            RunRecord run = repository.findRun(runId)
                    .orElseThrow(() -> new LedgerRunNotFoundException("run not found: " + runId));
            var steps = repository.findSteps(runId).stream()
                    .map(step -> new StepView(step.stepKey(),
                            step.fromStatus() == null ? null : step.fromStatus().name(),
                            step.toStatus() == null ? null : step.toStatus().name(),
                            text(step.occurredAt()), step.dwellMs(), json(step.detailJson())))
                    .toList();
            var env = repository.findEnvs(runId).stream()
                    .map(row -> new EnvView(row.containerName(), row.imageTag(), row.imageId(),
                            row.javaVersion(), json(row.jvmArgsJson()), json(row.gcJson()),
                            text(row.capturedAt())))
                    .toList();
            var params = repository.findParams(runId).stream()
                    .map(row -> new ParamView(row.groupName(), json(row.payloadJson()),
                            text(row.savedAt())))
                    .toList();
            var checks = repository.findChecks(runId).stream()
                    .map(row -> new CheckView(row.checkKey(), row.expected(), row.actual(),
                            row.verdict(), text(row.checkedAt())))
                    .toList();
            // B2-04a: fault actions with this runId, oldest first. Never null.
            var faults = repository.findFaultEvents(runId).stream()
                    .map(row -> new FaultEventView(row.operationId(), row.source(), row.target(),
                            row.event(), text(row.occurredAt()), json(row.detailJson())))
                    .toList();
            return new RunDetailView(view(run), steps, env, params, checks, faults);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /** Always answerable: this is how a DEGRADED ledger is diagnosed. */
    public LedgerStatusView status() {
        LedgerBootstrap.BootstrapStatus current = bootstrap.status();
        String error = current.lastError() != null ? current.lastError() : writer.lastError();
        return new LedgerStatusView(current.state().name(), current.schemaVersion(),
                stash.pendingCount(), text(writer.lastWriteAt()), error, resolver.mode());
    }

    public LedgerRecoveryView recovery() {
        requireReady();
        try {
            List<RunView> runs = repository.findRunsByStatuses(EnumSet.of(RunStatus.UNKNOWN))
                    .stream().map(this::view).toList();
            List<OperationRecord> pending = repository.findOperationsByStatuses(
                    EnumSet.of(OperationStatus.REQUESTED, OperationStatus.PENDING_CONFIRMATION,
                            OperationStatus.UNKNOWN));
            List<String> suggestions = new ArrayList<>();
            for (RunView run : runs) {
                suggestions.add("run " + run.runId() + " (" + run.scenario()
                        + "): inspect leftover k6/traffic containers and Toxiproxy toxics once the controller"
                        + " is back, then close it as FAILED or ABORTED");
            }
            for (OperationRecord operation : pending) {
                suggestions.add("operation " + operation.operationId() + " (" + operation.action()
                        + " on " + operation.targetKind() + "/" + operation.targetName()
                        + "): verify the target's real state and record RESTORED or FAILED; do not assume"
                        + " it was reverted");
            }
            return new LedgerRecoveryView(runs, pending.stream().map(this::view).toList(), suggestions);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private void requireReady() {
        LedgerReadiness.requireReady(properties, bootstrap);
    }

    private LedgerUnavailableException unavailable(DataAccessException e) {
        return LedgerReadiness.unavailable(bootstrap, e, "ledger query");
    }

    private String nextRunId(Instant now) {
        String prefix = "run-" + DAY.format(now) + "-";
        int next;
        try {
            next = repository.maxRunSequence(prefix) + 1;
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
        if (next > 999) {
            throw new LedgerConflictException("daily run-id space exhausted for " + DAY.format(now));
        }
        return String.format(Locale.ROOT, "%s%03d", prefix, next);
    }

    private static String validateScenario(String scenario) {
        if (scenario == null || !SCENARIO.matcher(scenario).matches()) {
            throw new InvalidLedgerRequestException(
                    "scenario must match " + SCENARIO.pattern() + " (was '" + scenario + "')");
        }
        return scenario;
    }

    private static RunStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return RunStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidLedgerRequestException("unknown run status '" + status
                    + "'; expected one of " + java.util.Arrays.toString(RunStatus.values()));
        }
    }

    /** Rejects credential-looking keys instead of storing or hashing them. */
    private static void rejectSensitiveKeys(JsonNode node, String path) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (SENSITIVE_KEY.matcher(entry.getKey()).matches()) {
                    throw new InvalidLedgerRequestException("payload key '" + path + "."
                            + entry.getKey() + "' looks like a credential; ledger snapshots must not"
                            + " contain passwords, tokens or keys");
                }
                rejectSensitiveKeys(entry.getValue(), path + "." + entry.getKey());
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                rejectSensitiveKeys(node.get(i), path + "[" + i + "]");
            }
        }
    }

    private String serialize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new InvalidLedgerRequestException("run params are not serializable: " + e.getMessage());
        }
    }

    private JsonNode json(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(raw);
        }
    }

    private RunView view(RunRecord run) {
        return new RunView(run.runId(), run.scenario(), run.status().name(), run.sourceCommit(),
                run.operator(), text(run.createdAt()), text(run.updatedAt()),
                text(run.startedAt()), text(run.endedAt()));
    }

    private OperationView view(OperationRecord operation) {
        return new OperationView(operation.operationId(), operation.runId(), operation.targetKind(),
                operation.targetName(), operation.action(), operation.status().name(),
                operation.ttlSeconds(), text(operation.requestedAt()), text(operation.appliedAt()),
                text(operation.restoredAt()), operation.lastError(), operation.operator(),
                json(operation.beforeSummaryJson()), json(operation.afterSummaryJson()), false);
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
