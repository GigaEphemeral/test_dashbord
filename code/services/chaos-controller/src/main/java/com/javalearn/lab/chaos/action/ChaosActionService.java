package com.javalearn.lab.chaos.action;

import com.javalearn.lab.chaos.annotation.ChaosAnnotator;
import com.javalearn.lab.chaos.cluster.ProbeTargetGuard;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.config.DatastoreTargetPolicy;
import com.javalearn.lab.chaos.error.ChaosConflictException;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.error.InvalidChaosActionException;
import com.javalearn.lab.chaos.error.ProbeTargetProtectedException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Applies container chaos actions and guarantees a revert.
 *
 * <p>Reverting actions (kill/freeze) live in {@link #active} keyed by target so
 * a target can only have one pending revert (409). Each action gets a scheduled
 * revert task; a watchdog sweep is the second safety net if a task is lost.
 * A revert is only claimable after the faulting Docker call finished
 * ({@link ChaosActionStatus#APPLYING} gate), so a short TTL can never start a
 * container whose stop is still in flight. Stop-all keeps that gate but never
 * becomes a no-op: an APPLYING action gets a stop request whose revert fires
 * at the apply-completion checkpoint instead of waiting for the TTL. If the
 * apply fails with an indeterminate transport error the action stays tracked
 * as FAILED and the watchdog performs the revert. Revert failures stay visible
 * as FAILED and are retried up to {@code chaos.max-revert-attempts}. Every
 * Docker call goes through the gateway, which enforces the
 * {@code lab=java-learn} label.</p>
 *
 * <p>Stateful datastores are additionally gated by
 * {@link DatastoreTargetPolicy}: unless
 * {@code chaos.datastore.allowed-target-pattern} admits the name, every action
 * on a mysql/redis/rabbit/mongo/postgres container is refused with 403 before
 * anything else runs. Automatic TTL reverts bypass that rule on purpose - a
 * fault that was allowed to be applied must always be allowed to be undone.</p>
 *
 * <p>Probe isolation (Stage2 B0-08 slice B) is a second, independent refusal on
 * the same path: when {@code chaos.mysql-cluster.container} names the target of
 * a {@code kill}/{@code freeze}/{@code restart}, the action is refused with 403
 * {@code 40302} (see {@link ProbeTargetGuard}) - faulting the node the MySQL
 * cluster probe lives in freezes every {@code mysql_cluster_*} series in the
 * middle of the failover drill. {@code start}/{@code resume} stay allowed.</p>
 *
 * <p>The guide can freeze the whole action set: while paused, scheduled reverts
 * and the watchdog do not fire, and every action keeps its remaining TTL.
 * Resuming schedules that remaining TTL (not a fresh one); stop-all reverts
 * regardless of pause. {@code pause}/{@code resume}/{@code execute} serialize
 * on this service's monitor so a racing pause can never leave a full-TTL task
 * attached, and resume moves every expiry before it unpauses the watchdog.</p>
 *
 * <p>Documented limitations: a revert that already claimed its CAS may still
 * complete shortly after {@code pause} returns; FAILED retries stay frozen
 * until resume (pausing is deliberately "freeze the whole lab"); a plain
 * {@code stop-all} while paused keeps the paused flag - use
 * {@code POST /lab/guide/reset} (or resume) to unfreeze the service. A stop-all
 * that only deferred an APPLYING action is also subject to the pause: its
 * checkpoint revert waits for resume, where the watchdog then reverts it.</p>
 *
 * <p>Stop-all additionally calls {@link NetworkFaultCleaner#resetAll()}: network
 * toxics are not tracked as {@link ChaosAction}s (they have no Docker state to
 * probe and no TTL), but a demo must still be recoverable by the same button.
 * That cleanup is reported in {@link StopAllResult#toxiproxy()} and never
 * prevents the container reverts above.</p>
 */
@Service
public class ChaosActionService {

    private static final Logger log = LoggerFactory.getLogger(ChaosActionService.class);
    private static final String TAG_CHAOS = "chaos";
    private static final String TAG_LAB = "java-learn";

    private final ContainerGateway gateway;
    private final ChaosAnnotator annotator;
    private final ChaosProperties properties;
    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final DatastoreTargetPolicy datastorePolicy;
    private final ProbeTargetGuard probeTargetGuard;
    private final NetworkFaultCleaner networkFaultCleaner;

    private final ConcurrentHashMap<String, ChaosAction> active = new ConcurrentHashMap<>();
    private volatile boolean paused;

    @Autowired
    public ChaosActionService(ContainerGateway gateway,
                              ChaosAnnotator annotator,
                              ChaosProperties properties,
                              MeterRegistry meterRegistry,
                              ScheduledExecutorService chaosActionScheduler,
                              NetworkFaultCleaner networkFaultCleaner) {
        this(gateway, annotator, properties, meterRegistry, chaosActionScheduler,
                Clock.systemUTC(), networkFaultCleaner);
    }

    /** Test seam without a network cleaner (legacy constructor). */
    public ChaosActionService(ContainerGateway gateway,
                              ChaosAnnotator annotator,
                              ChaosProperties properties,
                              MeterRegistry meterRegistry,
                              ScheduledExecutorService chaosActionScheduler) {
        this(gateway, annotator, properties, meterRegistry, chaosActionScheduler,
                Clock.systemUTC(), NetworkFaultCleaner.NONE);
    }

    /** Test seam: a controllable clock makes freeze/resume remaining-TTL deterministic. */
    public ChaosActionService(ContainerGateway gateway,
                              ChaosAnnotator annotator,
                              ChaosProperties properties,
                              MeterRegistry meterRegistry,
                              ScheduledExecutorService chaosActionScheduler,
                              Clock clock) {
        this(gateway, annotator, properties, meterRegistry, chaosActionScheduler, clock,
                NetworkFaultCleaner.NONE);
    }

    /**
     * Canonical constructor: the network cleaner is part of stop-all, so tests
     * can record that stop-all really resets Toxiproxy.
     */
    public ChaosActionService(ContainerGateway gateway,
                              ChaosAnnotator annotator,
                              ChaosProperties properties,
                              MeterRegistry meterRegistry,
                              ScheduledExecutorService chaosActionScheduler,
                              Clock clock,
                              NetworkFaultCleaner networkFaultCleaner) {
        this.gateway = gateway;
        this.annotator = annotator;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.scheduler = chaosActionScheduler;
        this.clock = clock;
        this.networkFaultCleaner = networkFaultCleaner == null
                ? NetworkFaultCleaner.NONE : networkFaultCleaner;
        // Fail fast: an invalid chaos.datastore.allowed-target-pattern must stop
        // the context, not surface as a 500 on the first fault request.
        this.datastorePolicy = DatastoreTargetPolicy.from(properties);
        // B0-08 slice B: the MySQL cluster probe container is never a legal
        // destructive target (read straight from chaos.mysql-cluster.*).
        this.probeTargetGuard = ProbeTargetGuard.from(properties);

        Gauge.builder("chaos.active.actions", active, map -> map.size())
                .description("Chaos actions tracked with a pending automatic revert")
                .register(meterRegistry);

        int interval = properties.getWatchdogIntervalSeconds();
        if (interval > 0) {
            scheduler.scheduleAtFixedRate(this::sweep, interval, interval, TimeUnit.SECONDS);
        }
    }

    /**
     * Applies one action. Whitelist/existence checks run before any Docker call,
     * so 404/403 win over the 409 conflict check.
     */
    public ChaosActionView execute(String targetName, ChaosActionRequest request) {
        String target = targetName == null ? "" : targetName.trim();
        if (target.isEmpty()) {
            throw new InvalidChaosActionException("container name is required");
        }
        ChaosActionType type = ChaosActionType.parse(request == null ? null : request.action());
        Integer ttl = resolveTtl(type, request == null ? null : request.ttlSeconds());

        // Datastore rule (M3-1c): closed by default, so a fault on a
        // datastore-named container needs chaos.datastore.allowed-target-pattern.
        // Pure regex - still no Docker call before a refusal, and the reason
        // names the rule that refused.
        String datastoreRejection = datastorePolicy.rejectionReason(target);
        if (datastoreRejection != null) {
            countAction(type, target, "rejected");
            throw new ContainerNotAllowedException(datastoreRejection);
        }

        ContainerView targetView = requireLabTarget(type, target);
        // Docker resolves names *and* id-prefixes to the same container, so the
        // keyword test above can be slipped past by requesting an id reference.
        // Re-run the allow-list against the container the reference actually
        // resolved to; only an inspect-reported canonical name can differ here.
        String canonical = targetView == null ? null : targetView.name();
        if (canonical != null && !canonical.equals(target)) {
            String canonicalRejection = datastorePolicy.rejectionReason(canonical);
            if (canonicalRejection != null) {
                countAction(type, target, "rejected");
                throw new ContainerNotAllowedException(
                        "container reference \"" + target + "\" resolves to " + canonicalRejection);
            }
        }

        // Probe isolation (B0-08 slice B): the container the MySQL cluster probe
        // execs into is never a legal destructive target - faulting it blinds the
        // drill exactly while it should report the failover. Checked against the
        // canonical name so an id reference cannot slip past, and only for
        // kill/freeze/restart; start/resume are what bring the probe back.
        if (type.destructive()) {
            String probeRejection = probeTargetGuard.rejectionReason(canonical != null ? canonical : target);
            if (probeRejection != null) {
                countAction(type, target, "rejected");
                log.warn("Chaos {} on {} refused by the probe isolation rule: {}",
                        type.wireName(), target, probeRejection);
                throw new ProbeTargetProtectedException(probeRejection);
            }
        }

        if (!type.reverting()) {
            applyOneShot(type, target);
            ChaosActionView view = new ChaosActionView(UUID.randomUUID().toString(), target,
                    type.wireName(), null, null, null, ChaosActionStatus.COMPLETED.wireName(), null);
            safeAnnotate(oneShotTags(type, target), oneShotText(type, target));
            probeReadinessAsync(target, type);
            return view;
        }

        Instant now = clock.instant();
        ChaosAction action = new ChaosAction(UUID.randomUUID().toString(), target, type, ttl,
                now, now.plusSeconds(ttl));
        // Register and schedule under the pause/resume monitor: otherwise a
        // pause() landing between the `paused` check and schedule() could not
        // cancel this future (freeze runs before it exists), leaving a full-TTL
        // task that reverts early once the guide resumes.
        synchronized (this) {
            register(action);
            if (paused) {
                // Freeze immediately: the action stays visible with its full TTL
                // remaining and is scheduled when the guide resumes.
                action.freeze(now);
            } else {
                // Schedule before touching Docker so a lost thread can never leave
                // the container without a revert path; while the fault is still
                // applying the revert task claims nothing (see
                // ChaosActionStatus.APPLYING) and the expiry is handled right
                // after apply finishes (or by the watchdog).
                try {
                    long delayMillis = TimeUnit.SECONDS.toMillis(ttl);
                    ScheduledFuture<?> future = scheduler.schedule(
                            () -> autoRevert(action, "ttl"), delayMillis, TimeUnit.MILLISECONDS);
                    action.schedule(future);
                    if (paused) {
                        // Defensive double-check: pause ran on this thread while
                        // scheduling; drop the late task and freeze again.
                        action.cancelSchedule();
                        action.freeze(now);
                    }
                } catch (RejectedExecutionException e) {
                    active.remove(target, action);
                    throw e;
                }
            }
        }

        try {
            applyReverting(action);
        } catch (DockerUnavailableException e) {
            // Indeterminate outcome: the daemon may have applied the stop/signal
            // even though the response was lost. Keep the action tracked as
            // FAILED so the watchdog still issues the revert (start/SIGCONT);
            // a revert on a never-faulted container is an idempotent no-op.
            action.cancelSchedule();
            action.recordFailure(e);
            countAction(type, target, "failed");
            throw e;
        } catch (RuntimeException e) {
            // Definite "fault was not applied" error (unknown container, or the
            // target was not running for SIGSTOP): discard the action.
            action.cancelSchedule();
            active.remove(target, action);
            countAction(type, target, "failed");
            throw e;
        }
        action.markApplied();
        boolean stopRequested = action.isStopRequested();
        if (stopRequested || !action.expiresAt().isAfter(clock.instant())) {
            // Either the apply itself outlived the TTL (slow stop + short TTL),
            // or stop-all fired while the fault was still applying: revert now
            // instead of waiting for the next watchdog sweep.
            autoRevert(action, stopRequested ? "stop-all" : "ttl");
        }

        countAction(type, target, "accepted");
        log.info("Chaos {} applied to {} (id={}, ttl={}s, expiresAt={})",
                type.wireName(), target, action.id(), ttl, action.expiresAt());
        safeAnnotate(actionTags(action),
                "chaos " + type.wireName() + " on " + target
                        + " id=" + action.id()
                        + " ttl=" + ttl + "s"
                        + " expires=" + action.expiresAt()
                        + " revert=" + type.revertAction().wireName());
        return action.toView();
    }

    /** Snapshot of actions that still carry a pending or failed revert. */
    public List<ChaosActionView> activeActions() {
        return active.values().stream()
                .sorted(Comparator.comparing(ChaosAction::expiresAt))
                .map(ChaosAction::toView)
                .toList();
    }

    /** True while TTL reverts and watchdog retries are frozen. */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Freezes every tracked action, including actions not created by the guide.
     * Scheduled reverts are cancelled and each remaining TTL is captured.
     *
     * @return true when this call paused the service, false if it was already paused
     */
    public synchronized boolean pause() {
        if (paused) {
            return false;
        }
        paused = true;
        Instant now = clock.instant();
        for (ChaosAction action : active.values()) {
            long remaining = action.freeze(now);
            log.info("Froze chaos action {} ({} on {}): remaining={}ms",
                    action.id(), action.type().wireName(), action.target(), remaining);
        }
        return true;
    }

    /**
     * Restores every ACTIVE action with its remaining TTL (never a full reset)
     * and lets the watchdog retry FAILED reverts again.
     *
     * <p>All frozen expiries are moved to {@code now + remaining} while the
     * service is still paused; only then is the paused flag dropped. A watchdog
     * sweep landing between the two phases therefore never sees the stale
     * (already past) frozen expiry and cannot revert earlier than the
     * remaining TTL.</p>
     *
     * @return true when this call resumed the service, false if it was not paused
     */
    public synchronized boolean resume() {
        if (!paused) {
            return false;
        }
        Instant now = clock.instant();
        List<ChaosAction> resuming = new ArrayList<>();
        for (ChaosAction action : active.values()) {
            if (action.status() != ChaosActionStatus.ACTIVE) {
                continue;
            }
            long remaining = action.remainingMillis();
            if (remaining < 0) {
                // Defensive: pause never froze this action, so there is no
                // remaining TTL to restore. Keep the original expiry (its own
                // task or the watchdog reverts it); never treat "-1" as zero,
                // which would revert the moment we resume.
                log.warn("Chaos action {} ({} on {}) has no frozen remainder on resume;"
                                + " keeping expiry {}",
                        action.id(), action.type().wireName(), action.target(), action.expiresAt());
                continue;
            }
            action.resumeAt(now, remaining);
            resuming.add(action);
            log.info("Resumed chaos action {} ({} on {}): remaining={}ms",
                    action.id(), action.type().wireName(), action.target(), remaining);
        }
        paused = false;
        for (ChaosAction action : resuming) {
            long delayMillis = Math.max(0L, Duration.between(clock.instant(), action.expiresAt()).toMillis());
            try {
                ScheduledFuture<?> future = scheduler.schedule(
                        () -> autoRevert(action, "ttl"), delayMillis, TimeUnit.MILLISECONDS);
                action.schedule(future);
                if (paused) {
                    // pause() ran on this thread while rescheduling; refreeze.
                    action.cancelSchedule();
                    action.freeze(clock.instant());
                }
            } catch (RejectedExecutionException e) {
                // The watchdog (now unpaused) still reverts once expiresAt passes.
                log.warn("Could not reschedule {} on {} after resume; watchdog will revert: {}",
                        action.type().wireName(), action.target(), e.toString());
            }
        }
        return true;
    }

    /**
     * Reverts every tracked action immediately ({@code start}/{@code SIGCONT}),
     * cancelling scheduled reverts. Successfully reverted actions are cleared;
     * failures stay visible as FAILED. Actions whose faulting Docker call is
     * still running (APPLYING) cannot be reverted yet — reverting would race
     * the in-flight stop/SIGSTOP — so they are flagged with a stop request,
     * their TTL task is cancelled and the revert fires at the apply-completion
     * checkpoint (or via the watchdog if the apply errors indeterminate). They
     * are reported as {@code pending}, never as reverted.
     */
    public StopAllResult stopAll() {
        int reverted = 0;
        int failed = 0;
        int pending = 0;
        for (ChaosAction action : List.copyOf(active.values())) {
            action.cancelSchedule();
            if (action.requestStopWhileApplying()) {
                pending++;
                log.warn("stop-all: action {} ({} on {}) is still applying its fault; revert deferred to the "
                        + "apply-completion checkpoint (TTL task cancelled; will NOT wait for expiry)",
                        action.id(), action.type().wireName(), action.target());
                continue;
            }
            if (!action.claimRevert()) {
                // A revert is already running concurrently; it fulfils the stop-all intent.
                continue;
            }
            action.nextRevertAttempt();
            try {
                performRevert(action);
                finishRevert(action, "stop-all");
                reverted++;
            } catch (RuntimeException e) {
                action.recordFailure(e);
                countRevert(action.type(), "failed");
                failed++;
                log.error("stop-all revert of {} on {} failed: {}", action.type().wireName(),
                        action.target(), e.toString());
                safeAnnotate(actionTags(action),
                        "chaos stop-all revert " + action.type().wireName() + " on " + action.target()
                                + " id=" + action.id() + " failed: " + e.getMessage());
            }
        }
        log.warn("Chaos stop-all finished: reverted={} failed={} pending={}", reverted, failed, pending);
        NetworkResetReport network = resetNetworkFaults();
        return new StopAllResult(reverted, failed, pending, network);
    }

    /**
     * Network faults have no TTL action record, so they are outside the loop
     * above; stop-all still restores them so one button recovers the demo.
     * Failures are reported, never thrown: container reverts already happened
     * and must not be reported as a failed stop-all because Toxiproxy is down.
     */
    private NetworkResetReport resetNetworkFaults() {
        NetworkResetReport report;
        try {
            report = networkFaultCleaner.resetAll();
        } catch (RuntimeException e) {
            log.error("Network-fault cleanup failed after stop-all: {}", e.toString());
            return new NetworkResetReport(NetworkResetReport.STATUS_FAILED, 0, 0,
                    List.of("network cleanup failed: " + e.getMessage()));
        }
        if (report == null) {
            return new NetworkResetReport(NetworkResetReport.STATUS_FAILED, 0, 0,
                    List.of("network cleaner returned no report"));
        }
        if (!NetworkResetReport.STATUS_RESET.equals(report.status())
                && !NetworkResetReport.STATUS_DISABLED.equals(report.status())) {
            log.warn("Network-fault cleanup after stop-all: status={} reset={}/{} errors={}",
                    report.status(), report.proxiesReset(), report.proxiesTotal(), report.errors());
        }
        return report;
    }

    /** Watchdog second safety net; also retries FAILED reverts. */
    void sweep() {
        try {
            if (paused) {
                // The guide is inspecting the lab: no TTL may fire, no retry may run.
                return;
            }
            Instant now = clock.instant();
            for (ChaosAction action : active.values()) {
                if (action.expiredAt(now)) {
                    log.warn("Watchdog: action {} ({} on {}) expired without revert; reverting",
                            action.id(), action.type().wireName(), action.target());
                    autoRevert(action, "watchdog");
                } else if (action.status() == ChaosActionStatus.FAILED
                        && action.revertAttempts() < properties.getMaxRevertAttempts()) {
                    log.warn("Watchdog: retrying failed revert {} ({} on {}, attempt {}/{})",
                            action.id(), action.type().wireName(), action.target(),
                            action.revertAttempts() + 1, properties.getMaxRevertAttempts());
                    autoRevert(action, "watchdog-retry");
                }
            }
        } catch (RuntimeException e) {
            // A throwing periodic task would be cancelled by the scheduler; the
            // watchdog must keep running as the last line of defence.
            log.error("Chaos watchdog sweep failed (will retry next interval): {}", e.toString());
        }
    }

    private void autoRevert(ChaosAction action, String trigger) {
        if (paused) {
            // A TTL task that fires just after pause must not undo the freeze.
            log.debug("Skipping {} revert of {} on {} (guide paused)", trigger,
                    action.type().wireName(), action.target());
            return;
        }
        if (!action.claimRevert()) {
            return;
        }
        int attempt = action.nextRevertAttempt();
        try {
            performRevert(action);
            finishRevert(action, trigger);
            log.info("Reverted {} on {} (id={}, trigger={}, attempt={})",
                    action.type().wireName(), action.target(), action.id(), trigger, attempt);
        } catch (RuntimeException e) {
            action.recordFailure(e);
            countRevert(action.type(), "failed");
            log.error("Revert of {} on {} failed (id={}, trigger={}, attempt={}/{}): {}",
                    action.type().wireName(), action.target(), action.id(), trigger, attempt,
                    properties.getMaxRevertAttempts(), e.toString());
            safeAnnotate(actionTags(action),
                    "chaos revert " + action.type().wireName() + " on " + action.target()
                            + " id=" + action.id() + " trigger=" + trigger
                            + " FAILED: " + e.getMessage());
        }
    }

    private void finishRevert(ChaosAction action, String trigger) {
        action.markReverted();
        active.remove(action.target(), action);
        countRevert(action.type(), "success");
        safeAnnotate(actionTags(action),
                "chaos reverted " + action.type().wireName() + " on " + action.target()
                        + " id=" + action.id()
                        + " ttl=" + action.ttlSeconds() + "s"
                        + " revert=" + action.type().revertAction().wireName()
                        + " trigger=" + trigger);
        probeReadinessAsync(action.target(), action.type().revertAction());
    }

    private void applyReverting(ChaosAction action) {
        if (action.type() == ChaosActionType.KILL) {
            // Manual stop: `unless-stopped` must not immediately restart the container.
            gateway.stop(action.target(), properties.getStopGraceSeconds());
        } else {
            gateway.signal(action.target(), ContainerGateway.SIGSTOP);
        }
    }

    private void performRevert(ChaosAction action) {
        switch (action.type()) {
            case KILL -> gateway.start(action.target());
            case FREEZE -> gateway.signal(action.target(), ContainerGateway.SIGCONT);
            default -> throw new IllegalStateException("Not a reverting action: " + action.type());
        }
    }

    private void applyOneShot(ChaosActionType type, String target) {
        try {
            switch (type) {
                case START -> gateway.start(target);
                case RESTART -> gateway.restart(target, properties.getStopGraceSeconds());
                case RESUME -> gateway.signal(target, ContainerGateway.SIGCONT);
                default -> throw new IllegalStateException("Not a one-shot action: " + type);
            }
        } catch (RuntimeException e) {
            countAction(type, target, "failed");
            throw e;
        }
        countAction(type, target, "accepted");
        log.info("Chaos {} applied to {}", type.wireName(), target);
    }

    private void register(ChaosAction action) {
        ChaosAction existing = active.putIfAbsent(action.target(), action);
        if (existing == null) {
            return;
        }
        boolean replaced;
        // Same monitor as ChaosAction.claimRevert(): either the watchdog has
        // already claimed the FAILED revert (status REVERTING -> 409) or we
        // supersede atomically, so the replaced action can never run a revert
        // that would abort the freshly applied replacement.
        synchronized (existing) {
            replaced = existing.status() == ChaosActionStatus.FAILED
                    && active.replace(action.target(), existing, action);
            if (replaced) {
                existing.markSuperseded();
            }
        }
        if (replaced) {
            // A failed action never reverted; let the operator try again.
            existing.cancelSchedule();
            log.warn("Replacing failed chaos action {} on {} with {}", existing.id(), action.target(),
                    action.id());
            return;
        }
        countAction(action.type(), action.target(), "rejected");
        throw new ChaosConflictException("Target " + action.target()
                + " already has active chaos action " + existing.id()
                + " (" + existing.type().wireName() + ", expires " + existing.expiresAt() + ")");
    }

    private ContainerView requireLabTarget(ChaosActionType type, String target) {
        try {
            return gateway.inspectLabContainer(target);
        } catch (ContainerNotAllowedException | ContainerNotFoundException e) {
            countAction(type, target, "rejected");
            throw e;
        }
    }

    private Integer resolveTtl(ChaosActionType type, Integer requestedTtl) {
        if (!type.reverting()) {
            // start/restart/resume have nothing to revert; drop any TTL.
            return null;
        }
        int max = properties.getMaxTtlSeconds();
        int defaultTtl = properties.getDefaultTtlSeconds();
        if (defaultTtl < 1 || max < defaultTtl) {
            throw new InvalidChaosActionException(
                    "chaos TTL configuration is invalid (default=" + defaultTtl + ", max=" + max + ")");
        }
        if (requestedTtl == null) {
            return defaultTtl;
        }
        if (requestedTtl < 1 || requestedTtl > max) {
            throw new InvalidChaosActionException(
                    "ttlSeconds must be between 1 and " + max + " (got " + requestedTtl + ")");
        }
        return requestedTtl;
    }

    private void probeReadinessAsync(String target, ChaosActionType actionType) {
        if (actionType == null) {
            return;
        }
        try {
            scheduler.execute(() -> awaitReady(target, actionType));
        } catch (RejectedExecutionException e) {
            log.debug("Scheduler is shutting down; skipping readiness probe for {}", target);
        }
    }

    private void awaitReady(String target, ChaosActionType actionType) {
        int timeoutSeconds = properties.getActionTimeoutSeconds();
        int intervalSeconds = Math.max(1, properties.getWatchdogIntervalSeconds());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            try {
                ContainerView view = gateway.inspectLabContainer(target);
                if (isReady(view)) {
                    log.info("{} on {} is ready (state={}, health={})", actionType.wireName(),
                            target, view.state(), view.health());
                    return;
                }
            } catch (RuntimeException e) {
                log.debug("Readiness probe for {} failed: {}", target, e.toString());
            }
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(intervalSeconds));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("{} on {} did not become healthy within {}s", actionType.wireName(), target,
                timeoutSeconds);
        countAction(actionType, target, "unready");
    }

    private static boolean isReady(ContainerView view) {
        if (view == null || !"running".equals(view.state())) {
            return false;
        }
        String health = view.health();
        return health == null || health.isBlank()
                || "none".equalsIgnoreCase(health)
                || "healthy".equalsIgnoreCase(health);
    }

    private List<String> actionTags(ChaosAction action) {
        List<String> tags = new ArrayList<>(List.of(TAG_CHAOS, TAG_LAB, action.target(),
                action.type().wireName()));
        tags.addAll(extraTags());
        return tags;
    }

    private List<String> oneShotTags(ChaosActionType type, String target) {
        List<String> tags = new ArrayList<>(List.of(TAG_CHAOS, TAG_LAB, target, type.wireName()));
        tags.addAll(extraTags());
        return tags;
    }

    private List<String> extraTags() {
        List<String> configured = properties.getAnnotation().getTags();
        if (configured == null) {
            return List.of();
        }
        return configured.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .toList();
    }

    private String oneShotText(ChaosActionType type, String target) {
        return "chaos " + type.wireName() + " on " + target + " (one-shot, no TTL)";
    }

    private void safeAnnotate(List<String> tags, String text) {
        try {
            annotator.annotate(tags, text);
        } catch (RuntimeException e) {
            // Annotations are best-effort and must never fail or revert an action.
            log.warn("Chaos annotation failed (ignored): {}", e.toString());
        }
    }

    private void countAction(ChaosActionType type, String target, String result) {
        meterRegistry.counter("chaos.actions",
                        "action", type.wireName(), "target", target, "result", result)
                .increment();
    }

    private void countRevert(ChaosActionType type, String result) {
        meterRegistry.counter("chaos.reverts", "action", type.wireName(), "result", result)
                .increment();
    }
}
