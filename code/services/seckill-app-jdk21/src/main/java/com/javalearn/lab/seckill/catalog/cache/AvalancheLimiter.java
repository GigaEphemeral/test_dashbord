package com.javalearn.lab.seckill.catalog.cache;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The bounded DB-fallback budget of the circuit-breaking avalanche mode
 * (design 8.4 雪崩 row: 熔断降级到 DB + 限流).
 *
 * <p>When an avalanche drains L1 and L2, every miss would otherwise reach
 * MySQL at once. This limiter admits at most {@code permits} database reads per
 * {@code period-ms} window and rejects the rest immediately, so the stampede
 * degrades into a bounded stream of {@code 429}/503-style answers instead of
 * overwhelming the database — the "circuit half-open" behaviour the lab
 * demonstrates, reduced to the one mechanism the experiment needs.</p>
 *
 * <p>A fixed window (not a token bucket) is deliberate: it is trivially
 * explainable on the console and deterministic under an injected clock. The
 * clock and both numbers are externalized ({@code app.lab.cache.avalanche
 * .db-fallback.*}); nothing is hardcoded here.</p>
 */
public final class AvalancheLimiter {

    private final long permits;
    private final long periodMillis;
    private final LongSupplier nowMillis;

    private long windowStart = Long.MIN_VALUE;
    private long usedInWindow;
    private long acquired;
    private long rejected;

    public AvalancheLimiter(long permits, long periodMillis, LongSupplier nowMillis) {
        if (permits <= 0L) {
            throw new IllegalArgumentException("Permits must be positive: " + permits);
        }
        if (periodMillis <= 0L) {
            throw new IllegalArgumentException("Period must be positive: " + periodMillis);
        }
        this.permits = permits;
        this.periodMillis = periodMillis;
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
    }

    /**
     * @return {@code true} when this read may touch the database inside the
     *         current window; {@code false} once the budget is exhausted
     */
    public synchronized boolean tryAcquire() {
        long now = nowMillis.getAsLong();
        if (windowStart == Long.MIN_VALUE || now - windowStart >= periodMillis) {
            windowStart = now;
            usedInWindow = 0L;
        }
        if (usedInWindow < permits) {
            usedInWindow++;
            acquired++;
            return true;
        }
        rejected++;
        return false;
    }

    public long permits() {
        return permits;
    }

    public long periodMillis() {
        return periodMillis;
    }

    /** Lifetime count of admitted database reads, for {@code /lab/cache/state}. */
    public synchronized long acquired() {
        return acquired;
    }

    /** Lifetime count of fast-failed reads, for {@code /lab/cache/state}. */
    public synchronized long rejected() {
        return rejected;
    }
}
