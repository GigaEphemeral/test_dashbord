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

    /** Configuration versus reality for the seeded dataset. */
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
