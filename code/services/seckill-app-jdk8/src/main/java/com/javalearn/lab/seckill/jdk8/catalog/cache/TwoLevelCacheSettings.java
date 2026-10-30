package com.javalearn.lab.seckill.jdk8.catalog.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * Externalized configuration of {@link TwoLevelCache}: the Caffeine L1 sizing
 * plus the Redis L2 write TTL, key layout and {@code clear()} SCAN hint.
 *
 * <p>The production values come from {@code app.lab.cache.preheat.l1.*} and
 * {@code app.lab.cache.l2.*} (see {@code LabCachePreheatProperties} and
 * {@link com.javalearn.lab.seckill.jdk8.lab.config.LabCacheL2Properties}); the
 * no-arg constructor path of {@link TwoLevelCache} uses {@link #defaults()} so
 * existing callers and unit tests keep the historical behaviour (10k / 60s L1,
 * 5 minute L2 TTL, {@code cache:} prefix). Refresh is the one new default
 * (10s): the design's runtime story is "serve L1, refresh it asynchronously
 * from the shared L2 Redis", not "wait for the 60s write expiry". Since B0-04
 * the L2 numbers are configuration as well — {@link #DEFAULT_L2_TTL} et al. are
 * only the direct-construction (test/tool) fallback, never the running
 * application's source of truth.</p>
 *
 * <p>Java 8 shape: the JDK 21 peer is a record; this is an immutable class with
 * the same accessor names (and the same canonical-constructor validation), so
 * the two shells stay behaviourally identical.</p>
 */
public final class TwoLevelCacheSettings {

    private final long maximumSize;
    private final Duration refreshAfterWrite;
    private final Duration expireAfterWrite;
    private final boolean micrometerStatsEnabled;
    private final Duration l2Ttl;
    private final String l2KeyPrefix;
    private final long l2ClearScanCount;
    private final Duration expireAfterAccess;
    private final boolean l1Enabled;

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
                DEFAULT_L2_TTL, DEFAULT_L2_KEY_PREFIX, DEFAULT_L2_CLEAR_SCAN_COUNT,
                Duration.ZERO, true);
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
                DEFAULT_L2_TTL, DEFAULT_L2_KEY_PREFIX, DEFAULT_L2_CLEAR_SCAN_COUNT,
                Duration.ZERO, true);
    }

    /**
     * B0-04 shape: the seven fields bound from {@code app.lab.cache.*} before
     * B4 added the optional access expiry and the L1 startup switch.
     */
    public TwoLevelCacheSettings(long maximumSize,
                                 Duration refreshAfterWrite,
                                 Duration expireAfterWrite,
                                 boolean micrometerStatsEnabled,
                                 Duration l2Ttl,
                                 String l2KeyPrefix,
                                 long l2ClearScanCount) {
        this(maximumSize, refreshAfterWrite, expireAfterWrite, micrometerStatsEnabled,
                l2Ttl, l2KeyPrefix, l2ClearScanCount, Duration.ZERO, true);
    }

    public TwoLevelCacheSettings(long maximumSize,
                                 Duration refreshAfterWrite,
                                 Duration expireAfterWrite,
                                 boolean micrometerStatsEnabled,
                                 Duration l2Ttl,
                                 String l2KeyPrefix,
                                 long l2ClearScanCount,
                                 Duration expireAfterAccess,
                                 boolean l1Enabled) {
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
        if (l2KeyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("l2KeyPrefix must not be blank");
        }
        if (l2ClearScanCount <= 0L) {
            throw new IllegalArgumentException(
                    "l2ClearScanCount must be positive: " + l2ClearScanCount);
        }
        Objects.requireNonNull(expireAfterAccess, "expireAfterAccess");
        if (expireAfterAccess.isNegative()) {
            throw new IllegalArgumentException(
                    "expireAfterAccess must not be negative (0 disables it): " + expireAfterAccess);
        }
        this.maximumSize = maximumSize;
        this.refreshAfterWrite = refreshAfterWrite;
        this.expireAfterWrite = expireAfterWrite;
        this.micrometerStatsEnabled = micrometerStatsEnabled;
        this.l2Ttl = l2Ttl;
        this.l2KeyPrefix = l2KeyPrefix;
        this.l2ClearScanCount = l2ClearScanCount;
        this.expireAfterAccess = expireAfterAccess;
        this.l1Enabled = l1Enabled;
    }

    /**
     * Historical L1/L2 defaults, used only when a caller constructs the cache
     * directly (tests, tools); the Spring application always passes the bound
     * {@code app.lab.cache.preheat.l1.*} / {@code app.lab.cache.l2.*} values.
     */
    public static TwoLevelCacheSettings defaults() {
        return new TwoLevelCacheSettings(
                10_000L, Duration.ofSeconds(10), Duration.ofSeconds(60), true,
                DEFAULT_L2_TTL, DEFAULT_L2_KEY_PREFIX, DEFAULT_L2_CLEAR_SCAN_COUNT,
                Duration.ZERO, true);
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

    public long maximumSize() {
        return maximumSize;
    }

    public Duration refreshAfterWrite() {
        return refreshAfterWrite;
    }

    public Duration expireAfterWrite() {
        return expireAfterWrite;
    }

    public boolean micrometerStatsEnabled() {
        return micrometerStatsEnabled;
    }

    public Duration l2Ttl() {
        return l2Ttl;
    }

    public String l2KeyPrefix() {
        return l2KeyPrefix;
    }

    public long l2ClearScanCount() {
        return l2ClearScanCount;
    }

    public Duration expireAfterAccess() {
        return expireAfterAccess;
    }

    public boolean l1Enabled() {
        return l1Enabled;
    }
}
