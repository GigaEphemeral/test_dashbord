package com.javalearn.lab.seckill.lab.api;

/**
 * {@code GET /lab/cache/state} payload: read-only observability of the cache
 * experiment. Counts are scoped to the reserved experiment id range, so the demo
 * baseline (products 1 and 20) never shows up here.
 *
 * <p>When Redis is unreachable the {@code redisReachable} flag is false and the
 * Redis-derived counts are zero: the endpoint degrades to what it can prove from
 * the database and the local Caffeine cache instead of failing.</p>
 */
public record CacheStateResponse(Defense defense,
                                 Breakdown breakdown,
                                 Avalanche avalanche,
                                 Dataset dataset,
                                 Keys keys,
                                 Bloom bloom,
                                 NullCache nullCache) {

    /** The two runtime switches that decide what a product read actually does. */
    public record Defense(String mode, boolean cacheToggleEnabled) {
    }

    /**
     * What is protecting the hot key right now: {@code strategy} is the current
     * mode key ({@code mutex} / {@code logical-expire}, or the penetration mode
     * when no breakdown defense is active) plus the live guard gauges, so the
     * console can show the active strategy without guessing from metrics.
     */
    public record Breakdown(String strategy,
                            boolean mutexActive,
                            boolean logicalExpireActive,
                            long mutexWaitMs,
                            long mutexLeaseMs,
                            String mutexOnLockTimeout,
                            long logicalTtlSeconds,
                            long retentionSeconds,
                            int refreshPoolSize,
                            int refreshQueueCapacity,
                            long staleEntriesCached,
                            long refreshInFlight) {
    }

    /**
     * What is protecting the product cache against an L2 avalanche right now:
     * the current mode key ({@code ttl-jitter} / {@code l1-cache} /
     * {@code db-fallback}, or whichever other defense is active) plus every
     * parameter of the avalanche strategies and their live counters, so the
     * console can show the active strategy without inferring it from metrics.
     *
     * <p>{@code l1StaleWindowSeconds} is the documented worst-case staleness of
     * the {@code l1-cache} posture; {@code limiterPermits}/{@code limiterPeriodMs}
     * are the DB-fallback budget and {@code limiterRejected} the fast-failed
     * reads of this instance.</p>
     */
    public record Avalanche(String strategy,
                            boolean ttlJitterActive,
                            double ttlJitterPercent,
                            boolean l1CacheActive,
                            long l1RefreshIntervalSeconds,
                            long l1StaleWindowSeconds,
                            long l1StaleEntriesCached,
                            long l1RefreshInFlight,
                            boolean dbFallbackActive,
                            long limiterPermits,
                            long limiterPeriodMs,
                            long limiterAcquired,
                            long limiterRejected) {
    }

    public record Dataset(long startId,
                          long endId,
                          long productCount,
                          long rowsInRange,
                          long missingRows,
                          long avalancheKeyCount,
                          long avalancheTtlSeconds,
                          long hotKeyProductId) {
    }

    /** Live key counts; L1 is zero while the {@code cache} toggle is off. */
    public record Keys(boolean redisReachable,
                       long l1ExperimentKeys,
                       long l2ExperimentKeys,
                       long l2AvalancheKeysAlive,
                       boolean hotKeyCached) {
    }

    /** Bloom filter geometry; {@code initialised} false means bloom protects nothing. */
    public record Bloom(String key,
                        boolean initialised,
                        long size,
                        long capacity,
                        double falseProbability) {
    }

    /** Marker store geometry plus how many misses are currently remembered. */
    public record NullCache(String keyPrefix,
                            long ttlSeconds,
                            long jitterSeconds,
                            long liveMarkerKeys) {
    }
}
