package com.javalearn.lab.seckill.catalog.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * Externalized configuration of {@link TwoLevelCache}: the Caffeine L1 sizing
 * plus the Redis L2 write TTL, key layout and {@code clear()} SCAN hint.
 *
 * <p>The production values come from {@code app.lab.cache.preheat.l1.*} and
 * {@code app.lab.cache.l2.*} (see {@code LabCachePreheatProperties} and
 * {@link com.javalearn.lab.seckill.lab.config.LabCacheL2Properties}); the
 * no-arg constructor path of {@link TwoLevelCache} uses {@link #defaults()} so
 * existing callers and unit tests keep the historical behaviour (10k / 60s L1,
 * 5 minute L2 TTL, {@code cache:} prefix). Refresh is the one new default
 * (10s): the design's runtime story is "serve L1, refresh it asynchronously
 * from the shared L2 Redis", not "wait for the 60s write expiry". Since B0-04
 * the L2 numbers are configuration as well — {@link #DEFAULT_L2_TTL} et al. are
 * only the direct-construction (test/tool) fallback, never the running
 * application's source of truth.</p>
 *
 * @param maximumSize       W-TinyLFU admission window size (design 8.4: 10k)
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
 * @param l2Ttl             Redis write TTL of every L2 entry
 *                          ({@code app.lab.cache.l2.ttl-seconds})
 * @param l2KeyPrefix       prefix of the L2 key layout
 *                          ({@code app.lab.cache.l2.key-prefix}); a key is
 *                          {@code <l2KeyPrefix><cacheName>:<key>}
 * @param l2ClearScanCount  SCAN COUNT hint used by {@code clear()} when it
 *                          collects the cache's L2 keys
 *                          ({@code app.lab.cache.l2.clear-scan-count})
 */
public record TwoLevelCacheSettings(long maximumSize,
                                    Duration refreshAfterWrite,
                                    Duration expireAfterWrite,
                                    boolean micrometerStatsEnabled,
                                    Duration l2Ttl,
                                    String l2KeyPrefix,
                                    long l2ClearScanCount) {

    /**
     * Direct-construction (test/tool) fallback for the L2 numbers, kept as the
     * historical constants so callers that predate B0-04 still compile. The
     * Spring application always passes the bound {@code app.lab.cache.l2.*}
     * values instead.
     */
    static final Duration DEFAULT_L2_TTL = Duration.ofMinutes(5);
    static final String DEFAULT_L2_KEY_PREFIX = "cache:";
    static final long DEFAULT_L2_CLEAR_SCAN_COUNT = 500L;

    /**
     * Historical three-argument shape: stats registration on, L2 values at the
     * documented fallbacks. Test and tool callers keep compiling; the Spring
     * path binds every field explicitly.
     */
    public TwoLevelCacheSettings(long maximumSize,
                                 Duration refreshAfterWrite,
                                 Duration expireAfterWrite) {
        this(maximumSize, refreshAfterWrite, expireAfterWrite, true,
                DEFAULT_L2_TTL, DEFAULT_L2_KEY_PREFIX, DEFAULT_L2_CLEAR_SCAN_COUNT);
    }

    /**
     * B0-03 shape: L1 sizing plus the stats flag, L2 values at the documented
     * fallbacks.
     */
    public TwoLevelCacheSettings(long maximumSize,
                                 Duration refreshAfterWrite,
                                 Duration expireAfterWrite,
                                 boolean micrometerStatsEnabled) {
        this(maximumSize, refreshAfterWrite, expireAfterWrite, micrometerStatsEnabled,
                DEFAULT_L2_TTL, DEFAULT_L2_KEY_PREFIX, DEFAULT_L2_CLEAR_SCAN_COUNT);
    }

    public TwoLevelCacheSettings {
        Objects.requireNonNull(refreshAfterWrite, "refreshAfterWrite");
        Objects.requireNonNull(expireAfterWrite, "expireAfterWrite");
        Objects.requireNonNull(l2Ttl, "l2Ttl");
        Objects.requireNonNull(l2KeyPrefix, "l2KeyPrefix");
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
        if (l2Ttl.isZero() || l2Ttl.isNegative()) {
            throw new IllegalArgumentException("l2Ttl must be positive: " + l2Ttl);
        }
        if (l2KeyPrefix.isBlank()) {
            throw new IllegalArgumentException("l2KeyPrefix must not be blank");
        }
        if (l2ClearScanCount <= 0L) {
            throw new IllegalArgumentException(
                    "l2ClearScanCount must be positive: " + l2ClearScanCount);
        }
    }

    /**
     * Historical L1/L2 defaults, used only when a caller constructs the cache
     * directly (tests, tools); the Spring application always passes the bound
     * {@code app.lab.cache.preheat.l1.*} / {@code app.lab.cache.l2.*} values.
     */
    public static TwoLevelCacheSettings defaults() {
        return new TwoLevelCacheSettings(
                10_000L, Duration.ofSeconds(10), Duration.ofSeconds(60), true,
                DEFAULT_L2_TTL, DEFAULT_L2_KEY_PREFIX, DEFAULT_L2_CLEAR_SCAN_COUNT);
    }

    /**
     * The L2 key prefix of one cache: {@code <l2KeyPrefix><cacheName>:}. This
     * is the single source of the key layout, shared by the cache itself and by
     * the lab seeder, state reader, preheat service and TTL-jitter adjuster, so
     * a changed prefix moves every reader and writer together.
     */
    public String l2PrefixFor(String cacheName) {
        return l2KeyPrefix + cacheName + ":";
    }
}
