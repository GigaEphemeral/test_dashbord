package com.javalearn.lab.seckill.catalog.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * Externalized sizing of the Caffeine L1 inside {@link TwoLevelCache}.
 *
 * <p>The production values come from {@code app.lab.cache.preheat.l1.*} (see
 * {@code LabCachePreheatProperties}); the no-arg constructor path of
 * {@link TwoLevelCache} uses {@link #defaults()} so existing callers and unit
 * tests keep the historical 10k / 60s behaviour. Refresh is the one new default
 * (10s): the design's runtime story is "serve L1, refresh it asynchronously
 * from the shared L2 Redis", not "wait for the 60s write expiry".</p>
 *
 * @param maximumSize      W-TinyLFU admission window size (design 8.4: 10k)
 * @param refreshAfterWrite how old an entry may be before a read triggers an
 *                          asynchronous L2 reload (design 8.4: 10s)
 * @param expireAfterWrite  hard physical lifetime of an L1 entry, the final
 *                          freshness bound even when refresh keeps failing
 *                          (design 8.4: 60s)
 * @param micrometerStatsEnabled whether the Caffeine-native L1 stats series
 *                          ({@code cache_l1_caffeine_*}, {@code cache_l1_estimated_size})
 *                          are registered per cache instance; the production
 *                          value comes from
 *                          {@code app.lab.cache.preheat.l1.micrometer-stats-enabled}
 */
public record TwoLevelCacheSettings(long maximumSize,
                                    Duration refreshAfterWrite,
                                    Duration expireAfterWrite,
                                    boolean micrometerStatsEnabled) {

    /**
     * Historical three-argument shape: stats registration on. Test and tool
     * callers keep compiling; the Spring path binds the flag explicitly.
     */
    public TwoLevelCacheSettings(long maximumSize,
                                 Duration refreshAfterWrite,
                                 Duration expireAfterWrite) {
        this(maximumSize, refreshAfterWrite, expireAfterWrite, true);
    }

    public TwoLevelCacheSettings {
        Objects.requireNonNull(refreshAfterWrite, "refreshAfterWrite");
        Objects.requireNonNull(expireAfterWrite, "expireAfterWrite");
        if (maximumSize <= 0L) {
            throw new IllegalArgumentException("maximumSize must be positive: " + maximumSize);
        }
        if (refreshAfterWrite.isZero() || refreshAfterWrite.isNegative()) {
            throw new IllegalArgumentException(
                    "refreshAfterWrite must be positive: " + refreshAfterWrite);
        }
        if (expireAfterWrite.isZero() || expireAfterWrite.isNegative()) {
            throw new IllegalArgumentException(
                    "expireAfterWrite must be positive: " + expireAfterWrite);
        }
        if (expireAfterWrite.compareTo(refreshAfterWrite) <= 0) {
            throw new IllegalArgumentException(
                    "expireAfterWrite must exceed refreshAfterWrite: "
                            + expireAfterWrite + " <= " + refreshAfterWrite);
        }
    }

    /**
     * Historical L1 defaults, used only when a caller constructs the cache
     * directly (tests, tools); the Spring application always passes the bound
     * {@code app.lab.cache.preheat.l1.*} values.
     */
    public static TwoLevelCacheSettings defaults() {
        return new TwoLevelCacheSettings(
                10_000L, Duration.ofSeconds(10), Duration.ofSeconds(60));
    }
}
