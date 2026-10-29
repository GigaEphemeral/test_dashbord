package com.javalearn.lab.chaos.action;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable state of an active reverting action. All status transitions are CAS
 * based so the per-action scheduled revert, the watchdog sweep and stop-all can
 * race safely without double-reverting a container.
 *
 * <p>Guide pause freezes the countdown: {@link #freeze(Instant)} cancels the
 * scheduled revert and records the remaining time; {@link #resumeAt(Instant)}
 * moves the expiry forward by exactly that remaining time.</p>
 *
 * <p>A <b>manual hold</b> (design §16.8, {@code ttlSeconds=-1}) is a reverting
 * action with no expiry: {@code ttlSeconds} and {@code expiresAt} are both
 * {@code null}, so it never counts as expired, the watchdog never auto-reverts
 * it and pause/resume have no countdown to freeze or restore. Only stop-all (or
 * an explicit revert) ends it.</p>
 */
final class ChaosAction {

    private final String id;
    private final String target;
    private final ChaosActionType type;
    private final Integer ttlSeconds;
    private final Instant createdAt;
    private volatile Instant expiresAt;
    private final AtomicReference<ChaosActionStatus> status =
            new AtomicReference<>(ChaosActionStatus.APPLYING);
    private final AtomicInteger revertAttempts = new AtomicInteger();
    private volatile boolean stopRequested;
    private volatile ScheduledFuture<?> scheduled;
    private volatile long remainingMillis = -1;
    private volatile String lastError;

    ChaosAction(String id, String target, ChaosActionType type, Integer ttlSeconds, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.target = target;
        this.type = type;
        this.ttlSeconds = ttlSeconds;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    String id() {
        return id;
    }

    String target() {
        return target;
    }

    ChaosActionType type() {
        return type;
    }

    Integer ttlSeconds() {
        return ttlSeconds;
    }

    /** True for a manual hold: a reverting action with no automatic revert. */
    boolean manualHold() {
        return expiresAt == null;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    ChaosActionStatus status() {
        return status.get();
    }

    int nextRevertAttempt() {
        return revertAttempts.incrementAndGet();
    }

    int revertAttempts() {
        return revertAttempts.get();
    }

    String lastError() {
        return lastError;
    }

    void recordFailure(Throwable error) {
        this.lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
        status.set(ChaosActionStatus.FAILED);
    }

    /**
     * Marks the faulting Docker call (stop/SIGSTOP) as finished. Only from now
     * on may a revert be claimed; reverting early would race the still-running
     * stop and could leave the container permanently down.
     * Synchronized so it pairs atomically with {@link #requestStopWhileApplying()}
     * under the action monitor: a stop-all flag can never be written in between
     * the transition to ACTIVE and the checkpoint read in
     * {@code ChaosActionService.execute()} and then be missed.
     */
    synchronized boolean markApplied() {
        return status.compareAndSet(ChaosActionStatus.APPLYING, ChaosActionStatus.ACTIVE);
    }

    /**
     * Stop-all intent for a fault that is still applying. Returns true only
     * while APPLYING; the flag is then consumed at the apply-completion
     * checkpoint so the revert (start/SIGCONT) runs immediately once the fault
     * lands instead of waiting for the cancelled TTL task. Returns false for
     * every other status, letting stop-all revert the action directly.
     */
    synchronized boolean requestStopWhileApplying() {
        if (status.get() == ChaosActionStatus.APPLYING) {
            stopRequested = true;
            return true;
        }
        return false;
    }

    synchronized boolean isStopRequested() {
        return stopRequested;
    }

    /**
     * Claims the revert. Succeeds from ACTIVE or FAILED (watchdog retry);
     * concurrent callers lose and do nothing. Never succeeds while the fault
     * is still applying. Synchronized on {@code this} so {@code register()} can
     * atomically supersede a FAILED action under the same monitor.
     */
    synchronized boolean claimRevert() {
        ChaosActionStatus current = status.get();
        if (current != ChaosActionStatus.ACTIVE && current != ChaosActionStatus.FAILED) {
            return false;
        }
        status.set(ChaosActionStatus.REVERTING);
        return true;
    }

    /**
     * Retires a FAILED action that a newer action replaced. Must be called
     * under the action monitor ({@code register()}) so a watchdog claim racing
     * in between can no longer revert the replaced action.
     */
    synchronized void markSuperseded() {
        status.set(ChaosActionStatus.REVERTED);
    }

    void markReverted() {
        status.set(ChaosActionStatus.REVERTED);
    }

    void schedule(ScheduledFuture<?> future) {
        this.scheduled = future;
    }

    void cancelSchedule() {
        ScheduledFuture<?> future = scheduled;
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Cancels the scheduled revert and captures the remaining TTL. A zero or
     * negative remainder is clamped to zero so resume reverts immediately
     * instead of losing the action. An action whose fault is still applying has
     * no task yet but must still keep its countdown, so APPLYING is captured too.
     * A manual hold has no countdown at all and keeps {@code -1} ("not frozen").
     */
    long freeze(Instant now) {
        cancelSchedule();
        ChaosActionStatus current = status();
        if (expiresAt != null
                && (current == ChaosActionStatus.ACTIVE || current == ChaosActionStatus.APPLYING)) {
            remainingMillis = Math.max(0L, Duration.between(now, expiresAt).toMillis());
        }
        return remainingMillis;
    }

    /** Remaining frozen TTL in millis, or -1 when the action is not frozen. */
    long remainingMillis() {
        return remainingMillis;
    }

    /**
     * B2-04a API countdown. Returns {@code null} when the countdown is no longer
     * meaningful: a manual hold (no auto-revert), the action failed (the
     * watchdog owns the retry) or was reverted. While paused the frozen
     * remainder is authoritative - the live expiry has deliberately not moved
     * yet - otherwise the expiry decides. Callers pass the service's monitor
     * state and injected clock, never {@code System.currentTimeMillis()}.
     */
    Long remainingViewMillis(Instant now, boolean paused) {
        ChaosActionStatus current = status();
        if (current == ChaosActionStatus.REVERTED || current == ChaosActionStatus.FAILED) {
            return null;
        }
        if (expiresAt == null) {
            // Manual hold: the view renders null as "no auto-revert".
            return null;
        }
        if (paused) {
            long frozen = remainingMillis;
            return frozen < 0 ? null : frozen;
        }
        return Math.max(0L, Duration.between(now, expiresAt).toMillis());
    }

    /**
     * Moves the expiry to {@code now + remaining} and returns the new expiry,
     * or {@code null} for a manual hold, whose expiry never moves.
     */
    Instant resumeAt(Instant now, long ttlMillis) {
        if (expiresAt == null) {
            return null;
        }
        expiresAt = now.plusMillis(Math.max(0L, ttlMillis));
        remainingMillis = -1;
        return expiresAt;
    }

    /**
     * True when the action is ACTIVE and its (possibly frozen) expiry has
     * passed. A manual hold has no expiry and is never expired, so neither the
     * watchdog nor a TTL task can auto-revert it.
     */
    boolean expiredAt(Instant now) {
        return status() == ChaosActionStatus.ACTIVE
                && expiresAt != null
                && !expiresAt.isAfter(now);
    }

    ChaosActionView toView() {
        return new ChaosActionView(id, target, type.wireName(), ttlSeconds, expiresAt,
                type.revertAction() == null ? null : type.revertAction().wireName(),
                status().wireName(), lastError);
    }
}
