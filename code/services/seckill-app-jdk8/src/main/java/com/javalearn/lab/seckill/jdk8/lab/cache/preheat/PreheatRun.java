package com.javalearn.lab.seckill.jdk8.lab.cache.preheat;

import java.time.Instant;
import java.util.Objects;

/**
 * The outcome of one preheat attempt. {@code requested} is the number of ids
 * read from the shared hot ZSet; on a successful run
 * {@code loaded + skippedFresh + missing + failed == requested}. A run that
 * could not read the hot list (Redis down) or that lost the cache instance
 * reports the reason in {@code outcome}/{@code error}.
 */
public final class PreheatRun {

    private final PreheatTrigger trigger;
    private final Instant startedAt;
    private final long durationMs;
    private final int requested;
    private final int loaded;
    private final int skippedFresh;
    private final int missing;
    private final int failed;
    private final Outcome outcome;
    private final String error;

    public PreheatRun(PreheatTrigger trigger,
                      Instant startedAt,
                      long durationMs,
                      int requested,
                      int loaded,
                      int skippedFresh,
                      int missing,
                      int failed,
                      Outcome outcome,
                      String error) {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(outcome, "outcome");
        this.trigger = trigger;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.requested = requested;
        this.loaded = loaded;
        this.skippedFresh = skippedFresh;
        this.missing = missing;
        this.failed = failed;
        this.outcome = outcome;
        this.error = error;
    }

    public PreheatTrigger trigger() {
        return trigger;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public long durationMs() {
        return durationMs;
    }

    public int requested() {
        return requested;
    }

    public int loaded() {
        return loaded;
    }

    public int skippedFresh() {
        return skippedFresh;
    }

    public int missing() {
        return missing;
    }

    public int failed() {
        return failed;
    }

    public Outcome outcome() {
        return outcome;
    }

    public String error() {
        return error;
    }

    /** Terminal state of a run, keyed for the lab JSON/console. */
    public enum Outcome {
        SUCCESS("success"),
        /** The cache lab toggle is off, so there is no L1 to warm. */
        CACHE_DISABLED("cache-disabled"),
        /** Another run holds the single-flight guard; nothing was attempted. */
        BUSY("busy"),
        /** Reading the hot list or an L2 value failed; partial counts remain. */
        FAILED("failed");

        private final String key;

        Outcome(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
