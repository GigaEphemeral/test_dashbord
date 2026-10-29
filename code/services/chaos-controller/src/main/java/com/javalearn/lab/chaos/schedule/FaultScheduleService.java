package com.javalearn.lab.chaos.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.action.ChaosActionRequest;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.ChaosActionStatus;
import com.javalearn.lab.chaos.action.ChaosActionType;
import com.javalearn.lab.chaos.action.ChaosActionView;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.ledger.AuditSanitizer;
import com.javalearn.lab.chaos.ledger.FaultEventService;
import com.javalearn.lab.chaos.ledger.OperationContext;
import com.javalearn.lab.chaos.ledger.OperationOutcome;
import com.javalearn.lab.chaos.ledger.OperationRequest;
import com.javalearn.lab.chaos.ledger.OperationService;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * B3-00b run-scoped fault timeline executor.
 *
 * <p>On run start the lifecycle reads nothing itself: this service reloads the
 * persisted {@code faultSchedule} param group (the validator already normalised
 * it at create time), validates it again as a defence against hand-edited rows,
 * and schedules one task per entry at {@code runStart + atSeconds} on its own
 * daemon pool. Arming happens after the k6 launch but before READY→RUNNING; a
 * failure therefore travels through the existing {@code closeFailedStart} path
 * (k6 stopped, run FAILED), because scheduled faults are part of the experiment
 * and must never silently vanish.</p>
 *
 * <p>Each due entry is injected through {@link ChaosActionService} with the run
 * id attached and only then written to {@code lab_fault_event}; a failed action
 * writes an explicit {@code <action>-failed} timeline row instead of a false
 * "injected" one. The task re-checks the run status right before injecting, so
 * a fault that was already picked up while the run stops is skipped.</p>
 *
 * <p>Arming is idempotent per run ({@code arm} twice schedules once) and
 * {@link #cancel(String)} both cancels the futures and flips a per-run flag the
 * task checks, so a task already handed to a worker cannot inject after stop.</p>
 *
 * <p><b>Manual hold</b> (design §16.8). A {@code recovery=manual} kill/freeze
 * is sent to {@link ChaosActionService} as {@code ttlSeconds=-1}: the fault is
 * applied and tracked but no automatic revert is armed, so it stays in place
 * until stop-all (or an explicit revert). One-shot actions
 * (start/restart/resume) have nothing to revert and stay TTL-less, so a manual
 * entry on them sends no TTL at all.</p>
 */
public class FaultScheduleService implements FaultScheduler {

    private static final Logger log = LoggerFactory.getLogger(FaultScheduleService.class);
    private static final String GROUP = "faultSchedule";
    private static final String SOURCE = "schedule";
    private static final TypeReference<List<Map<String, Object>>> ENTRY_LIST =
            new TypeReference<>() {
            };

    private final RunLedgerRepository repository;
    private final ObjectMapper objectMapper;
    private final ChaosActionService chaosActions;
    private final FaultEventService faultEvents;
    private final OperationService operations;
    private final ScheduledExecutorService scheduler;
    private final FaultScheduleValidator validator;
    private final Clock clock;

    /** runId -> armed schedule; guarded by {@code this}. */
    private final Map<String, RunSchedule> armed = new HashMap<>();

    public FaultScheduleService(RunLedgerRepository repository, ObjectMapper objectMapper,
                                ChaosActionService chaosActions, FaultEventService faultEvents,
                                OperationService operations, ScheduledExecutorService scheduler,
                                FaultScheduleValidator validator, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.chaosActions = chaosActions;
        this.faultEvents = faultEvents;
        this.operations = operations;
        this.scheduler = scheduler;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    public synchronized int arm(String runId, Instant runStart) {
        RunSchedule existing = armed.get(runId);
        if (existing != null) {
            return existing.armedCount;
        }
        List<FaultScheduleEntry> entries = load(runId);
        RunSchedule schedule = new RunSchedule(runId, entries.size());
        armed.put(runId, schedule);
        try {
            for (FaultScheduleEntry entry : entries) {
                long delayMillis = delayMillis(runStart, entry.atSeconds());
                schedule.futures.add(scheduler.schedule(
                        () -> fire(schedule, entry), delayMillis, TimeUnit.MILLISECONDS));
            }
        } catch (RuntimeException e) {
            schedule.cancelled = true;
            cancelFutures(schedule);
            armed.remove(runId);
            throw new IllegalStateException("could not arm the fault schedule of run " + runId
                    + " (" + entries.size() + " entries): " + e.getMessage(), e);
        }
        return entries.size();
    }

    @Override
    public synchronized void cancel(String runId) {
        RunSchedule schedule = armed.remove(runId);
        if (schedule == null) {
            return;
        }
        schedule.cancelled = true;
        cancelFutures(schedule);
    }

    private long delayMillis(Instant runStart, int atSeconds) {
        long delay = Duration.between(clock.instant(), runStart.plusSeconds(atSeconds)).toMillis();
        return Math.max(0L, delay);
    }

    private void fire(RunSchedule schedule, FaultScheduleEntry entry) {
        if (schedule.cancelled) {
            return;
        }
        String runStatus = currentStatus(schedule.runId);
        if (!isActive(runStatus)) {
            log.info("Skipping fault schedule entry {} on run {}: run is {}", entry.action(),
                    schedule.runId, runStatus);
            return;
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("container", entry.target());
        before.put("action", entry.action());
        before.put("scheduled", true);
        before.put("atSeconds", entry.atSeconds());
        before.put("recovery", entry.recovery());
        AtomicBoolean attempted = new AtomicBoolean();
        try {
            // Scheduled faults are operations too: the same contract as an
            // explicit POST gives the timeline one lab_operation row and lets
            // the fault event carry that operationId. Non-strict keeps faults
            // usable while the ledger is down (events are then best-effort).
            operations.submit(new OperationRequest(schedule.runId, "container", entry.target(),
                    entry.action(), scheduledTtl(entry), null, before, false),
                    () -> {
                        attempted.set(true);
                        return inject(schedule, entry);
                    });
        } catch (RuntimeException e) {
            if (!attempted.get()) {
                // The operation never ran (executor/ledger refusal): the action
                // did not apply, so the timeline still gets an explicit failure.
                recordFailure(schedule.runId, entry, runStatus, AuditSanitizer.describe(e), null);
            }
            // When inject() ran it already wrote the failure event.
            log.warn("Scheduled {} on {} for run {} failed: {}", entry.action(), entry.target(),
                    schedule.runId, e.toString());
        }
    }

    /**
     * Runs inside the operation context, so the fault event can name the
     * operation row. Re-checks cancellation and the run status because the task
     * may have waited in the operation queue while the run stopped.
     */
    private OperationOutcome inject(RunSchedule schedule, FaultScheduleEntry entry) {
        String runStatus = currentStatus(schedule.runId);
        if (schedule.cancelled || !isActive(runStatus)) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("container", entry.target());
            after.put("action", entry.action());
            after.put("scheduled", true);
            after.put("skipped", true);
            after.put("runStatus", runStatus);
            return OperationOutcome.applied(after, null);
        }
        try {
            ChaosActionView view = chaosActions.execute(entry.target(),
                    new ChaosActionRequest(entry.action(), scheduledTtl(entry), schedule.runId));
            if (view == null || ChaosActionStatus.FAILED.wireName().equals(view.status())) {
                throw new IllegalStateException("scheduled " + entry.action() + " did not apply ("
                        + (view == null ? "no view" : view.status()) + ")");
            }
            // Only a really applied action writes the injected timeline row.
            faultEvents.recordContainerAction(schedule.runId,
                    OperationContext.currentOperationId().orElse(null), view.target(),
                    entry.action(), view.ttlSeconds(), view.status(), view.expiresAt(), runStatus);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("container", view.target());
            after.put("actionStatus", view.status());
            after.put("scheduled", true);
            after.put("expiresAt", view.expiresAt() == null ? null : view.expiresAt().toString());
            return OperationOutcome.applied(after, view);
        } catch (RuntimeException e) {
            // The action did not apply (or its outcome is indeterminate): never
            // claim an injection. The failure row keeps the timeline honest.
            recordFailure(schedule.runId, entry, runStatus, AuditSanitizer.describe(e),
                    OperationContext.currentOperationId().orElse(null));
            throw e;
        }
    }

    private static boolean isActive(String runStatus) {
        // READY is the short window between k6 launch and the RUNNING transition
        // (a 0-second entry is due exactly there); anything else means stop.
        return RunStatus.RUNNING.name().equals(runStatus)
                || RunStatus.READY.name().equals(runStatus);
    }

    private String currentStatus(String runId) {
        try {
            Optional<RunRecord> run = repository.findRun(runId);
            return run.map(record -> record.status().name()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not read run {} before a scheduled fault; skipping the entry: {}",
                    runId, e.toString());
            return null;
        }
    }

    private void recordFailure(String runId, FaultScheduleEntry entry, String runStatus,
                               String reason, String operationId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("atSeconds", entry.atSeconds());
        detail.put("recovery", entry.recovery());
        detail.put("ttlSeconds", scheduledTtl(entry));
        detail.put("reason", reason);
        faultEvents.recordEvent(runId, operationId, SOURCE, entry.target(),
                entry.action() + "-failed", detail, runStatus);
    }

    private List<FaultScheduleEntry> load(String runId) {
        List<RunLedgerRepository.RunParamRow> rows;
        try {
            rows = repository.findParams(runId);
        } catch (RuntimeException e) {
            throw new IllegalStateException("could not read the fault schedule of run " + runId
                    + ": " + e.getMessage(), e);
        }
        String payload = rows.stream()
                .filter(row -> GROUP.equals(row.groupName()))
                .map(RunLedgerRepository.RunParamRow::payloadJson)
                .findFirst()
                .orElse(null);
        if (payload == null) {
            return List.of();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("faultSchedule of run " + runId
                    + " is not valid JSON: " + e.getMessage(), e);
        }
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalStateException("faultSchedule of run " + runId
                    + " is not a JSON array");
        }
        List<Map<String, Object>> raw;
        try {
            raw = objectMapper.convertValue(node, ENTRY_LIST);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("faultSchedule of run " + runId
                    + " is not a list of objects: " + e.getMessage(), e);
        }
        try {
            return validator.validate(raw);
        } catch (InvalidLedgerRequestException e) {
            throw new IllegalStateException("persisted faultSchedule of run " + runId
                    + " is invalid: " + e.getMessage(), e);
        }
    }

    /**
     * TTL sent to {@link ChaosActionService} for one entry.
     *
     * <p>Auto entries keep their normalised TTL. A manual kill/freeze becomes
     * {@code -1} (design §16.8 manual hold: no automatic revert), while a manual
     * one-shot (start/restart/resume) stays TTL-less because it has nothing to
     * revert.</p>
     */
    private static Integer scheduledTtl(FaultScheduleEntry entry) {
        if (!entry.manual()) {
            return entry.ttlSeconds();
        }
        ChaosActionType type = ChaosActionType.maybeParse(entry.action()).orElse(null);
        return type != null && type.reverting() ? -1 : null;
    }

    private static void cancelFutures(RunSchedule schedule) {
        for (ScheduledFuture<?> future : schedule.futures) {
            future.cancel(false);
        }
    }

    /** Per-run arming state; {@code cancelled} is read by in-flight tasks. */
    private static final class RunSchedule {

        private final String runId;
        private final List<ScheduledFuture<?>> futures = new ArrayList<>();
        private final int armedCount;
        private volatile boolean cancelled;

        private RunSchedule(String runId, int armedCount) {
            this.runId = runId;
            this.armedCount = armedCount;
        }
    }
}
