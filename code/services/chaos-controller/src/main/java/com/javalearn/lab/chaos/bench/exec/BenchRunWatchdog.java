package com.javalearn.lab.chaos.bench.exec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Scenario;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Snapshot;
import com.javalearn.lab.chaos.error.LedgerConflictException;
import com.javalearn.lab.chaos.ledger.EnvLockService;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.LedgerWriter;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stage2 B2-02b-2a: settles k6 runs whose container has exited or overrun, and
 * removes k6 containers left behind by a previous controller process.
 *
 * <p><b>State path.</b> A successful natural exit is RUNNING → STOPPING
 * ({@code k6-exit}) → COMPLETED ({@code complete}), the same two-hop path the
 * operator stop uses. RUNNING → COMPLETED is not a legal edge and VERIFYING is
 * reserved for the B3 metrics/quality verdict, so the watchdog must not skip
 * the STOPPING checkpoint.</p>
 *
 * <p><b>First verdict wins.</b> Every decision is a compare-and-set transition
 * through {@link LedgerWriter}. A {@link LedgerConflictException} means the
 * operator stop (or another tick) already advanced the run: the tick logs and
 * moves on without touching the lock. A stashed transition ({@code false})
 * means the ledger was unavailable: the lock is kept and the next tick retries.
 * That is why the timeout path transitions first and only then stops/removes the
 * container - side effects never run for a run another path owns.</p>
 *
 * <p><b>Timeout.</b> requested duration = sum of the scenario's
 * {@code *_DURATION}/{@code *_RAMP_UP}/{@code *_RAMP_DOWN} values (manifest
 * defaults overlaid with the run's persisted {@code request} params), falling
 * back to {@code max-duration-seconds} when it cannot be computed. The deadline
 * is container {@code startedAt} (or the run row's {@code started_at} when
 * Docker has none) + requested duration + {@code timeout-grace-seconds}.</p>
 *
 * <p><b>Orphans.</b> The first tick whose ledger is ready lists
 * {@code lab.role=k6} containers and stops+removes every one whose
 * {@code lab.run-id} has no run or a run that is not RUNNING. It never touches a
 * RUNNING run's container. A ledger that is not ready defers the sweep to the
 * next tick; individual failures are retried, successful sweeps are not
 * repeated.</p>
 *
 * <p>All exceptions are caught inside the tick so a failing sweep can never
 * cancel the scheduled task. Unit tests call {@link #tick()} directly; the
 * daemon scheduler is only started by {@link #start()}.</p>
 */
public class BenchRunWatchdog {

    private static final Logger log = LoggerFactory.getLogger(BenchRunWatchdog.class);
    private static final String PARAM_GROUP = "request";

    private final BenchExecProperties properties;
    private final LedgerProperties ledgerProperties;
    private final LedgerBootstrap bootstrap;
    private final RunLedgerRepository repository;
    private final LedgerWriter writer;
    private final EnvLockService envLock;
    private final BenchRunExecutor executor;
    private final BenchScenarioCatalog catalog;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final AtomicBoolean orphanSweepDone = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;

    public BenchRunWatchdog(BenchExecProperties properties, LedgerProperties ledgerProperties,
                            LedgerBootstrap bootstrap, RunLedgerRepository repository,
                            LedgerWriter writer, EnvLockService envLock, BenchRunExecutor executor,
                            BenchScenarioCatalog catalog, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.ledgerProperties = ledgerProperties;
        this.bootstrap = bootstrap;
        this.repository = repository;
        this.writer = writer;
        this.envLock = envLock;
        this.executor = executor;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Starts the daemon scheduler; the first tick runs immediately. */
    public void start() {
        if (!properties.isWatchdogEnabled()) {
            log.info("k6 run watchdog is disabled (lab.bench.exec.watchdog-enabled=false)");
            return;
        }
        int interval = Math.max(1, properties.getWatchdogIntervalSeconds());
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bench-run-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        created.scheduleWithFixedDelay(this::tickSafely, 0, interval, TimeUnit.SECONDS);
        this.scheduler = created;
        log.info("k6 run watchdog started (interval={}s, timeout-grace={}s, orphan-cleanup={})",
                interval, properties.getTimeoutGraceSeconds(), properties.isOrphanCleanupEnabled());
    }

    /** Stops the scheduler (bean destroy); a no-op when it was never started. */
    public void stop() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            // A throwing periodic task would be cancelled by the scheduler; the
            // watchdog must keep running as the last line of defence.
            log.error("k6 run watchdog tick failed (will retry next interval): {}", e.toString());
        }
    }

    /** One sweep; public so a unit test can drive it without a thread. */
    public void tick() {
        if (!ledgerReady()) {
            log.debug("k6 run watchdog tick skipped: ledger is not ready");
            return;
        }
        sweepRunningRuns();
        if (!properties.isOrphanCleanupEnabled()) {
            orphanSweepDone.set(true);
        } else if (!orphanSweepDone.get()) {
            sweepOrphans();
        }
    }

    // ------------------------------------------------------------- running runs

    private void sweepRunningRuns() {
        List<RunRecord> runs;
        try {
            runs = repository.findRunsByStatuses(List.of(RunStatus.RUNNING));
        } catch (RuntimeException e) {
            log.warn("k6 run watchdog could not list RUNNING runs: {}", e.toString());
            return;
        }
        if (runs.isEmpty()) {
            return;
        }
        Snapshot snapshot = loadSnapshot();
        for (RunRecord run : runs) {
            try {
                settle(run, snapshot);
            } catch (RuntimeException e) {
                log.warn("k6 run watchdog could not settle run {}: {}", run.runId(), e.toString());
            }
        }
    }

    private void settle(RunRecord run, Snapshot snapshot) {
        BenchExecutionState state = executor.state(run.runId());
        String status = state.status();
        if (K6ContainerGateway.STATUS_ABSENT.equals(status)) {
            // Legacy/contract-first run, or the container was already removed:
            // never fail a run just because there is no container to inspect.
            log.debug("k6 run watchdog: run {} has no k6 container; leaving it alone", run.runId());
            return;
        }
        if (K6ContainerGateway.STATUS_EXITED.equals(status)) {
            Integer exitCode = state.exitCode();
            if (exitCode != null && exitCode == 0) {
                completeNaturally(run, state);
            } else {
                failAfterExit(run, state, exitCode);
            }
            return;
        }
        if (K6ContainerGateway.STATUS_RUNNING.equals(status) && timedOut(run, state, snapshot)) {
            failAfterTimeout(run, state, snapshot);
            return;
        }
        log.debug("k6 run watchdog: run {} container is {}; nothing to do", run.runId(), status);
    }

    private void completeNaturally(RunRecord run, BenchExecutionState state) {
        Map<String, Object> detail = outcomeDetail(state);
        if (!transition(run.runId(), RunStatus.RUNNING, RunStatus.STOPPING, "k6-exit", detail)) {
            return;
        }
        if (!transition(run.runId(), RunStatus.STOPPING, RunStatus.COMPLETED, "complete", detail)) {
            return;
        }
        releaseLock(run.runId(), "COMPLETED");
    }

    private void failAfterExit(RunRecord run, BenchExecutionState state, Integer exitCode) {
        Map<String, Object> detail = outcomeDetail(state);
        detail.put("exitCode", exitCode);
        if (!transition(run.runId(), RunStatus.RUNNING, RunStatus.FAILED,
                "k6-exit-failed", detail)) {
            return;
        }
        releaseLock(run.runId(), "FAILED (k6 exit " + exitCode + ")");
    }

    private void failAfterTimeout(RunRecord run, BenchExecutionState state, Snapshot snapshot) {
        double requestedSeconds = requestedDurationSeconds(snapshot, run.scenario(),
                readParams(run.runId()), properties.getMaxDurationSeconds());
        Instant startedAt = parseStartedAt(state.startedAt()).orElse(run.startedAt());
        Instant deadline = startedAt == null ? null
                : startedAt.plusMillis(TimeUnit.SECONDS.toMillis(
                        (long) Math.ceil(requestedSeconds + properties.getTimeoutGraceSeconds())));
        Map<String, Object> detail = outcomeDetail(state);
        detail.put("requestedSeconds", requestedSeconds);
        detail.put("timeoutGraceSeconds", properties.getTimeoutGraceSeconds());
        if (deadline != null) {
            detail.put("deadline", deadline.toString());
        }

        // Transition first: if another path already advanced the run, its
        // verdict wins and this tick must not stop a container it does not own.
        if (!transition(run.runId(), RunStatus.RUNNING, RunStatus.FAILED,
                "k6-timeout", detail)) {
            return;
        }
        try {
            boolean stopped = executor.stop(run.runId());
            boolean cleaned = executor.cleanup(run.runId());
            log.warn("k6 run {} exceeded its requested duration ({}s + {}s grace); stopped={},"
                            + " removed={}",
                    run.runId(), requestedSeconds, properties.getTimeoutGraceSeconds(),
                    stopped, cleaned);
        } catch (RuntimeException e) {
            // The run is FAILED either way; the orphan sweep is the safety net.
            log.warn("k6 run {} timed out but its container could not be cleaned: {}",
                    run.runId(), e.toString());
        }
        releaseLock(run.runId(), "FAILED (k6 timeout)");
    }

    private Map<String, Object> outcomeDetail(BenchExecutionState state) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("container", state.containerName());
        detail.put("exitCode", state.exitCode());
        detail.put("reportAvailable", state.reportAvailable());
        return detail;
    }

    // --------------------------------------------------------------- timeouts

    private boolean timedOut(RunRecord run, BenchExecutionState state, Snapshot snapshot) {
        Instant startedAt = parseStartedAt(state.startedAt()).orElse(run.startedAt());
        if (startedAt == null) {
            // Without a start time no deadline can be asserted; never guess.
            return false;
        }
        double requestedSeconds = requestedDurationSeconds(snapshot, run.scenario(),
                readParams(run.runId()), properties.getMaxDurationSeconds());
        Instant deadline = startedAt.plusMillis(TimeUnit.SECONDS.toMillis(
                (long) Math.ceil(requestedSeconds + properties.getTimeoutGraceSeconds())));
        return clock.instant().isAfter(deadline);
    }

    /**
     * Sums the scenario's duration-like parameters after overlaying the run's
     * persisted request params. Load's {@code LOAD_DURATION} is already the
     * total; spike contributes its four segments. A missing/unknown scenario or
     * an unparseable value falls back to {@code fallbackSeconds}.
     */
    static double requestedDurationSeconds(Snapshot snapshot, String scenarioName,
                                           Map<String, Object> overrides, double fallbackSeconds) {
        if (snapshot == null || snapshot.scenarios() == null || scenarioName == null) {
            return fallbackSeconds;
        }
        Scenario definition = snapshot.scenarios().get(scenarioName);
        if (definition == null) {
            return fallbackSeconds;
        }
        Map<String, Object> effective = new LinkedHashMap<>(definition.params());
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                if (effective.containsKey(key)) {
                    effective.put(key, value);
                }
            });
        }
        double total = 0;
        int counted = 0;
        for (Map.Entry<String, Object> entry : effective.entrySet()) {
            if (!BenchLaunchPlanner.isDurationKey(entry.getKey())) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            try {
                double seconds = value instanceof Number number
                        ? number.doubleValue()
                        : BenchLaunchPlanner.parseDurationSeconds(entry.getKey(),
                        String.valueOf(value));
                if (!Double.isFinite(seconds) || seconds < 0) {
                    return fallbackSeconds;
                }
                total += seconds;
                counted++;
            } catch (RuntimeException e) {
                return fallbackSeconds;
            }
        }
        return counted == 0 || total <= 0 ? fallbackSeconds : total;
    }

    private static Optional<Instant> parseStartedAt(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    // --------------------------------------------------------------- orphan sweep

    private void sweepOrphans() {
        List<K6ContainerGateway.ContainerSummary> containers;
        try {
            containers = executor.sweepOrphans();
        } catch (RuntimeException e) {
            log.warn("k6 orphan cleanup could not list k6 containers (will retry next tick): {}",
                    e.toString());
            return;
        }
        boolean settled = true;
        int removed = 0;
        for (K6ContainerGateway.ContainerSummary container : containers) {
            String runId = container.runId();
            if (runId == null || runId.isBlank()) {
                log.warn("k6 orphan cleanup: container {} has no lab.run-id label; leaving it",
                        container.containerName());
                continue;
            }
            try {
                Optional<RunRecord> run = repository.findRun(runId);
                if (run.isPresent() && run.get().status() == RunStatus.RUNNING) {
                    log.debug("k6 orphan cleanup: run {} is RUNNING; keeping {}",
                            runId, container.containerName());
                    continue;
                }
                executor.cleanup(runId);
                removed++;
                log.info("k6 orphan cleanup: removed {} (run {} is {})", container.containerName(),
                        runId, run.map(record -> record.status().name()).orElse("missing"));
            } catch (RuntimeException e) {
                settled = false;
                log.warn("k6 orphan cleanup of container {} (run {}) failed (will retry): {}",
                        container.containerName(), runId, e.toString());
            }
        }
        if (settled) {
            orphanSweepDone.set(true);
            log.info("k6 orphan cleanup finished: {} container(s) inspected, {} removed",
                    containers.size(), removed);
        }
    }

    // ---------------------------------------------------------------- ledger I/O

    private boolean ledgerReady() {
        try {
            return ledgerProperties.isEnabled() && bootstrap.isReady();
        } catch (RuntimeException e) {
            log.warn("k6 run watchdog cannot read ledger readiness: {}", e.toString());
            return false;
        }
    }

    private Snapshot loadSnapshot() {
        try {
            return catalog.load();
        } catch (RuntimeException e) {
            // Exit detection must not depend on the manifest; only timeout
            // calculation falls back to max-duration-seconds.
            log.warn("k6 run watchdog could not load the scenario manifest; timeout falls back to"
                    + " max-duration-seconds: {}", e.toString());
            return null;
        }
    }

    private Map<String, Object> readParams(String runId) {
        try {
            return repository.findParams(runId).stream()
                    .filter(param -> PARAM_GROUP.equals(param.groupName()))
                    .findFirst()
                    .map(param -> parseParams(param.payloadJson()))
                    .orElse(Map.of());
        } catch (RuntimeException e) {
            log.warn("k6 run watchdog could not read params of run {}; using manifest defaults: {}",
                    runId, e.toString());
            return Map.of();
        }
    }

    private Map<String, Object> parseParams(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> params = objectMapper.readValue(payloadJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            return params == null ? Map.of() : params;
        } catch (Exception e) {
            log.warn("k6 run watchdog could not parse run params; using manifest defaults: {}",
                    e.toString());
            return Map.of();
        }
    }

    /**
     * Compare-and-set transition. {@code false} means the ledger stashed the
     * event: the caller must keep the lock and let the next tick retry.
     */
    private boolean transition(String runId, RunStatus from, RunStatus to, String stepKey,
                               Map<String, Object> detail) {
        try {
            boolean written = writer.transition(runId, from, to, stepKey, clock.instant(),
                    detailJson(detail));
            if (!written) {
                log.warn("k6 run watchdog: {} {}->{} was stashed by the ledger; keeping the lock"
                        + " and retrying next tick", runId, from, to);
            }
            return written;
        } catch (LedgerConflictException e) {
            log.info("k6 run watchdog: run {} moved on before {} ({}); another path owns the verdict",
                    runId, stepKey, e.getMessage());
            return false;
        }
    }

    private void releaseLock(String runId, String reason) {
        EnvLockService.ReleaseReport release = envLock.release(runId);
        if (!release.released() && release.note() != null) {
            log.warn("k6 run watchdog closed run {} as {} but could not release the lock: {}",
                    runId, reason, release.note());
        }
    }

    private String detailJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            log.warn("k6 run watchdog could not render transition detail: {}", e.getMessage());
            return "{}";
        }
    }
}
