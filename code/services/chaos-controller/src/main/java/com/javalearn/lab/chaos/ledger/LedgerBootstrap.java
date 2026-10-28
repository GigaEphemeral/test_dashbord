package com.javalearn.lab.chaos.ledger;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns ledger availability: CREATE DATABASE, Flyway migration, one-shot restart
 * recovery, retention and periodic stash replay.
 *
 * <p>MySQL may be down when the controller starts (the primary-kill drill is a
 * first-class scenario). The Hikari pools therefore use
 * {@code initialization-fail-timeout=-1}, and bootstrap runs on its own
 * single-thread daemon scheduler. A failure only moves the ledger to
 * {@code DEGRADED} and schedules a retry; the Spring context keeps starting.
 * Exactly one recovery pass runs after the first successful migration - a
 * transient database outage after that must not turn live runs into
 * {@code UNKNOWN}.</p>
 */
public class LedgerBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LedgerBootstrap.class);

    public enum State {
        /** Schema migrated, queries allowed. */
        OK,
        /** MySQL/migration unusable; endpoints needing the ledger answer 503. */
        DEGRADED,
        /** No bootstrap attempt has completed yet. */
        NO_DATA
    }

    public record BootstrapStatus(State state, String schemaVersion, String lastError,
                                  Instant lastAttemptAt) {
    }

    private final LedgerProperties properties;
    private final LedgerTargetResolver resolver;
    private final JdbcTemplate serverJdbcTemplate;
    private final Flyway flyway;
    private final LedgerRecovery recovery;
    private final LedgerRetention retention;
    private final LedgerStash stash;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;

    private final AtomicReference<BootstrapStatus> status =
            new AtomicReference<>(new BootstrapStatus(State.NO_DATA, null, null, null));
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final AtomicBoolean periodicStarted = new AtomicBoolean();
    private volatile boolean recoveryDone;

    public LedgerBootstrap(LedgerProperties properties, LedgerTargetResolver resolver,
                           JdbcTemplate serverJdbcTemplate, Flyway flyway, LedgerRecovery recovery,
                           LedgerRetention retention, LedgerStash stash,
                           ScheduledExecutorService scheduler, Clock clock) {
        this.properties = properties;
        this.resolver = resolver;
        this.serverJdbcTemplate = serverJdbcTemplate;
        this.flyway = flyway;
        this.recovery = recovery;
        this.retention = retention;
        this.stash = stash;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        start();
    }

    public void start() {
        if (!properties.isEnabled()) {
            status.set(new BootstrapStatus(State.DEGRADED, null,
                    "lab.ledger.enabled=false; env LAB_LEDGER_ENABLED", clock.instant()));
            log.warn("Ledger disabled by lab.ledger.enabled=false; ledger endpoints will answer 503");
            return;
        }
        submit(this::bootstrapSafely);
    }

    public boolean isReady() {
        return status.get().state() == State.OK;
    }

    public BootstrapStatus status() {
        return status.get();
    }

    /**
     * Marks the ledger DEGRADED after a live query failed, and schedules the
     * same self-healing bootstrap retry used at startup.
     */
    public void recordFailure(String message) {
        BootstrapStatus previous = status.get();
        String safe = message == null || message.isBlank() ? "ledger query failed" : message;
        status.set(new BootstrapStatus(State.DEGRADED, previous.schemaVersion(), safe, clock.instant()));
        if (previous.state() != State.DEGRADED || !Objects.equals(previous.lastError(), safe)) {
            log.warn("Ledger became DEGRADED: {}", safe);
        }
        retrySoon();
    }

    private void bootstrapSafely() {
        Instant attemptedAt = clock.instant();
        try {
            String schema = properties.getSchema();
            serverJdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS `" + schema
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            flyway.migrate();
            String version = currentVersion();
            if (properties.isRecoveryEnabled() && !recoveryDone) {
                recovery.recover();
                recoveryDone = true;
            }
            retention.prune();
            status.set(new BootstrapStatus(State.OK, version, null, attemptedAt));
            log.info("Ledger ready: mode={}, schema={}, flywayVersion={}",
                    resolver.mode(), schema, version);
            startPeriodicTasks();
        } catch (RuntimeException e) {
            String message = rootMessage(e);
            BootstrapStatus previous = status.get();
            status.set(new BootstrapStatus(State.DEGRADED, previous.schemaVersion(), message, attemptedAt));
            if (previous.state() != State.DEGRADED || !Objects.equals(previous.lastError(), message)) {
                log.warn("Ledger bootstrap failed ({} mode); retrying in {}s: {}",
                        resolver.mode(), properties.getBootstrapRetrySeconds(), message);
            } else {
                log.debug("Ledger bootstrap still failing: {}", message);
            }
            retrySoon();
        }
    }

    private void startPeriodicTasks() {
        if (!periodicStarted.compareAndSet(false, true)) {
            return;
        }
        int replaySeconds = properties.getStashReplayIntervalSeconds();
        submitPeriodic(this::replaySafely, replaySeconds, replaySeconds, TimeUnit.SECONDS);
        int retentionMinutes = properties.getRetentionIntervalMinutes();
        submitPeriodic(this::retentionSafely, retentionMinutes, retentionMinutes, TimeUnit.MINUTES);
    }

    private void replaySafely() {
        if (!isReady()) {
            return;
        }
        try {
            stash.replay();
        } catch (RuntimeException e) {
            log.warn("Ledger stash replay tick failed: {}", rootMessage(e));
        }
    }

    private void retentionSafely() {
        if (!isReady()) {
            return;
        }
        try {
            retention.prune();
        } catch (RuntimeException e) {
            log.warn("Ledger retention tick failed: {}", rootMessage(e));
        }
    }

    private void retrySoon() {
        if (!retryScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.schedule(() -> {
                retryScheduled.set(false);
                bootstrapSafely();
            }, properties.getBootstrapRetrySeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            retryScheduled.set(false);
            log.debug("Ledger scheduler rejected a retry (context shutting down)");
        }
    }

    private void submit(Runnable task) {
        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException e) {
            log.debug("Ledger scheduler rejected the bootstrap task (context shutting down)");
        }
    }

    private void submitPeriodic(Runnable task, long initialDelay, long period, TimeUnit unit) {
        try {
            scheduler.scheduleWithFixedDelay(task, initialDelay, period, unit);
        } catch (RejectedExecutionException e) {
            log.debug("Ledger scheduler rejected a periodic task (context shutting down)");
        }
    }

    private String currentVersion() {
        MigrationInfo current = flyway.info().current();
        return current == null || current.getVersion() == null ? null : current.getVersion().toString();
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
