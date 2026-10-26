package com.javalearn.lab.chaos.action;

import com.javalearn.lab.chaos.annotation.ChaosAnnotator;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.ChaosConflictException;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.InvalidChaosActionException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * Revert failures stay visible as FAILED and are retried up to
 * {@code chaos.max-revert-attempts}. Every Docker call goes through the gateway,
 * which enforces the {@code lab=java-learn} label.</p>
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

    private final ConcurrentHashMap<String, ChaosAction> active = new ConcurrentHashMap<>();

    public ChaosActionService(ContainerGateway gateway,
                              ChaosAnnotator annotator,
                              ChaosProperties properties,
                              MeterRegistry meterRegistry,
                              ScheduledExecutorService chaosActionScheduler) {
        this.gateway = gateway;
        this.annotator = annotator;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.scheduler = chaosActionScheduler;

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

        requireLabTarget(type, target);

        if (!type.reverting()) {
            applyOneShot(type, target);
            ChaosActionView view = new ChaosActionView(UUID.randomUUID().toString(), target,
                    type.wireName(), null, null, null, ChaosActionStatus.COMPLETED.wireName());
            safeAnnotate(oneShotTags(type, target), oneShotText(type, target));
            probeReadinessAsync(target, type);
            return view;
        }

        Instant now = Instant.now();
        ChaosAction action = new ChaosAction(UUID.randomUUID().toString(), target, type, ttl,
                now, now.plusSeconds(ttl));
        register(action);
        // Schedule before touching Docker: if the apply below fails we cancel;
        // if scheduling itself failed nothing was stopped/frozen yet.
        try {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> autoRevert(action, "ttl"), ttl, TimeUnit.SECONDS);
            action.schedule(future);
        } catch (RejectedExecutionException e) {
            active.remove(target, action);
            throw e;
        }

        try {
            applyReverting(action);
        } catch (RuntimeException e) {
            action.cancelSchedule();
            active.remove(target, action);
            countAction(type, target, "failed");
            throw e;
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

    /**
     * Reverts every tracked action immediately ({@code start}/{@code SIGCONT}),
     * cancelling scheduled reverts. Successfully reverted actions are cleared;
     * failures stay visible as FAILED.
     */
    public StopAllResult stopAll() {
        int reverted = 0;
        int failed = 0;
        for (ChaosAction action : List.copyOf(active.values())) {
            action.cancelSchedule();
            if (!action.claimRevert()) {
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
        log.warn("Chaos stop-all finished: reverted={} failed={}", reverted, failed);
        return new StopAllResult(reverted, failed);
    }

    /** Watchdog second safety net; also retries FAILED reverts. */
    void sweep() {
        try {
            Instant now = Instant.now();
            for (ChaosAction action : active.values()) {
                if (action.status() == ChaosActionStatus.ACTIVE && !action.expiresAt().isAfter(now)) {
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
        if (existing.status() == ChaosActionStatus.FAILED
                && active.replace(action.target(), existing, action)) {
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

    private void requireLabTarget(ChaosActionType type, String target) {
        try {
            gateway.inspectLabContainer(target);
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
