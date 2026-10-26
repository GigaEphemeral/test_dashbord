package com.javalearn.lab.seckill.lab.cache.preheat;

import java.time.Instant;
import java.util.Objects;

/**
 * The outcome of one preheat attempt. {@code requested} is the number of ids
 * read from the shared hot ZSet; on a successful run
 * {@code loaded + skippedFresh + missing + failed == requested}. A run that
 * could not read the hot list (Redis down) or that lost the cache instance
 * reports the reason in {@code outcome}/{@code error}.
 */
public record PreheatRun(PreheatTrigger trigger,
                         Instant startedAt,
                         long durationMs,
                         int requested,
                         int loaded,
                         int skippedFresh,
                         int missing,
                         int failed,
                         Outcome outcome,
                         String error) {

    public PreheatRun {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(outcome, "outcome");
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
