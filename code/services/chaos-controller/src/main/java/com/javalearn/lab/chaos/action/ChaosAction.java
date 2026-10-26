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
 */
final class ChaosAction {

    private final String id;
    private final String target;
    private final ChaosActionType type;
    private final int ttlSeconds;
    private final Instant createdAt;
    private volatile Instant expiresAt;
    private final AtomicReference<ChaosActionStatus> status = new AtomicReference<>(ChaosActionStatus.ACTIVE);
    private final AtomicInteger revertAttempts = new AtomicInteger();
    private volatile ScheduledFuture<?> scheduled;
    private volatile long remainingMillis = -1;
    private volatile String lastError;

    ChaosAction(String id, String target, ChaosActionType type, int ttlSeconds, Instant createdAt, Instant expiresAt) {
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

    int ttlSeconds() {
        return ttlSeconds;
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
     * Claims the revert. Succeeds from ACTIVE or FAILED (watchdog retry);
     * concurrent callers lose and do nothing.
     */
    boolean claimRevert() {
        while (true) {
            ChaosActionStatus current = status.get();
            if (current != ChaosActionStatus.ACTIVE && current != ChaosActionStatus.FAILED) {
                return false;
            }
            if (status.compareAndSet(current, ChaosActionStatus.REVERTING)) {
                return true;
            }
        }
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
     * instead of losing the action.
     */
    long freeze(Instant now) {
        cancelSchedule();
        if (status() == ChaosActionStatus.ACTIVE) {
            remainingMillis = Math.max(0L, Duration.between(now, expiresAt).toMillis());
        }
        return remainingMillis;
    }

    /** Remaining frozen TTL in millis, or -1 when the action is not frozen. */
    long remainingMillis() {
        return remainingMillis;
    }

    /** Moves the expiry to {@code now + remaining} and returns the new expiry. */
    Instant resumeAt(Instant now, long ttlMillis) {
        expiresAt = now.plusMillis(Math.max(0L, ttlMillis));
        remainingMillis = -1;
        return expiresAt;
    }

    /** True when the action is ACTIVE and its (possibly frozen) expiry has passed. */
    boolean expiredAt(Instant now) {
        return status() == ChaosActionStatus.ACTIVE && !expiresAt.isAfter(now);
    }

    ChaosActionView toView() {
        return new ChaosActionView(id, target, type.wireName(), ttlSeconds, expiresAt,
                type.revertAction() == null ? null : type.revertAction().wireName(),
                status().wireName());
    }
}
