package com.javalearn.lab.seckill.jdk8.lab.api;

import java.util.Objects;

/**
 * {@code GET /lab/cache/state} payload: read-only observability of the cache
 * experiment. Counts are scoped to the reserved experiment id range, so the demo
 * baseline (products 1 and 20) never shows up here.
 *
 * <p>When Redis is unreachable the {@code redisReachable} flag is false and the
 * Redis-derived counts are zero: the endpoint degrades to what it can prove from
 * the database and the local Caffeine cache instead of failing.</p>
 *
 * <p>{@code l1Enabled} is the per-JVM Caffeine switch (B3-01), reported in the
 * same read so the console can show "not applicable" for L1 metrics without a
 * second request. It is independent of {@code defense.cacheToggleEnabled},
 * which controls both levels.</p>
 *
 * <p>Java 8 shape: the JDK 21 peer is a record tree; each node here is an
 * immutable class with the same accessor names plus JavaBean getters for
 * Jackson.</p>
 */
public final class CacheStateResponse {

    private final Defense defense;
    private final Breakdown breakdown;
    private final Avalanche avalanche;
    private final Dataset dataset;
    private final Keys keys;
    private final Bloom bloom;
    private final NullCache nullCache;
    private final boolean l1Enabled;

    public CacheStateResponse(Defense defense,
                              Breakdown breakdown,
                              Avalanche avalanche,
                              Dataset dataset,
                              Keys keys,
                              Bloom bloom,
                              NullCache nullCache,
                              boolean l1Enabled) {
        this.defense = defense;
        this.breakdown = breakdown;
        this.avalanche = avalanche;
        this.dataset = dataset;
        this.keys = keys;
        this.bloom = bloom;
        this.nullCache = nullCache;
        this.l1Enabled = l1Enabled;
    }

    public Defense getDefense() {
        return defense;
    }

    public Breakdown getBreakdown() {
        return breakdown;
    }

    public Avalanche getAvalanche() {
        return avalanche;
    }

    public Dataset getDataset() {
        return dataset;
    }

    public Keys getKeys() {
        return keys;
    }

    public Bloom getBloom() {
        return bloom;
    }

    public NullCache getNullCache() {
        return nullCache;
    }

    public boolean isL1Enabled() {
        return l1Enabled;
    }

    public Defense defense() {
        return defense;
    }

    public Breakdown breakdown() {
        return breakdown;
    }

    public Avalanche avalanche() {
        return avalanche;
    }

    public Dataset dataset() {
        return dataset;
    }

    public Keys keys() {
        return keys;
    }

    public Bloom bloom() {
        return bloom;
    }

    public NullCache nullCache() {
        return nullCache;
    }

    public boolean l1Enabled() {
        return l1Enabled;
    }

    /** The two runtime switches that decide what a product read actually does. */
    public static final class Defense {

        private final String mode;
        private final boolean cacheToggleEnabled;

        public Defense(String mode, boolean cacheToggleEnabled) {
            this.mode = mode;
            this.cacheToggleEnabled = cacheToggleEnabled;
        }

        public String getMode() {
            return mode;
        }

        public boolean isCacheToggleEnabled() {
            return cacheToggleEnabled;
        }

        public String mode() {
            return mode;
        }

        public boolean cacheToggleEnabled() {
            return cacheToggleEnabled;
        }
    }

    /**
     * What is protecting the hot key right now: {@code strategy} is the current
     * mode key ({@code mutex} / {@code logical-expire}, or the penetration mode
     * when no breakdown defense is active) plus the live guard gauges.
     */
    public static final class Breakdown {

        private final String strategy;
        private final boolean mutexActive;
        private final boolean logicalExpireActive;
        private final long mutexWaitMs;
        private final long mutexLeaseMs;
        private final String mutexOnLockTimeout;
        private final long logicalTtlSeconds;
        private final long retentionSeconds;
        private final int refreshPoolSize;
        private final int refreshQueueCapacity;
        private final long staleEntriesCached;
        private final long refreshInFlight;

        public Breakdown(String strategy,
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
            this.strategy = strategy;
            this.mutexActive = mutexActive;
            this.logicalExpireActive = logicalExpireActive;
            this.mutexWaitMs = mutexWaitMs;
            this.mutexLeaseMs = mutexLeaseMs;
            this.mutexOnLockTimeout = mutexOnLockTimeout;
            this.logicalTtlSeconds = logicalTtlSeconds;
            this.retentionSeconds = retentionSeconds;
            this.refreshPoolSize = refreshPoolSize;
            this.refreshQueueCapacity = refreshQueueCapacity;
            this.staleEntriesCached = staleEntriesCached;
            this.refreshInFlight = refreshInFlight;
        }

        public String getStrategy() {
            return strategy;
        }

        public boolean isMutexActive() {
            return mutexActive;
        }

        public boolean isLogicalExpireActive() {
            return logicalExpireActive;
        }

        public long getMutexWaitMs() {
            return mutexWaitMs;
        }

        public long getMutexLeaseMs() {
            return mutexLeaseMs;
        }

        public String getMutexOnLockTimeout() {
            return mutexOnLockTimeout;
        }

        public long getLogicalTtlSeconds() {
            return logicalTtlSeconds;
        }

        public long getRetentionSeconds() {
            return retentionSeconds;
        }

        public int getRefreshPoolSize() {
            return refreshPoolSize;
        }

        public int getRefreshQueueCapacity() {
            return refreshQueueCapacity;
        }

        public long getStaleEntriesCached() {
            return staleEntriesCached;
        }

        public long getRefreshInFlight() {
            return refreshInFlight;
        }

        public String strategy() {
            return strategy;
        }

        public boolean mutexActive() {
            return mutexActive;
        }

        public boolean logicalExpireActive() {
            return logicalExpireActive;
        }

        public long mutexWaitMs() {
            return mutexWaitMs;
        }

        public long mutexLeaseMs() {
            return mutexLeaseMs;
        }

        public String mutexOnLockTimeout() {
            return mutexOnLockTimeout;
        }

        public long logicalTtlSeconds() {
            return logicalTtlSeconds;
        }

        public long retentionSeconds() {
            return retentionSeconds;
        }

        public int refreshPoolSize() {
            return refreshPoolSize;
        }

        public int refreshQueueCapacity() {
            return refreshQueueCapacity;
        }

        public long staleEntriesCached() {
            return staleEntriesCached;
        }

        public long refreshInFlight() {
            return refreshInFlight;
        }
    }

    /**
     * What is protecting the product cache against an L2 avalanche right now:
     * the current mode key plus every parameter of the avalanche strategies and
     * their live counters.
     */
    public static final class Avalanche {

        private final String strategy;
        private final boolean ttlJitterActive;
        private final double ttlJitterPercent;
        private final boolean l1CacheActive;
        private final long l1RefreshIntervalSeconds;
        private final long l1StaleWindowSeconds;
        private final long l1StaleEntriesCached;
        private final long l1RefreshInFlight;
        private final boolean dbFallbackActive;
        private final long limiterPermits;
        private final long limiterPeriodMs;
        private final long limiterAcquired;
        private final long limiterRejected;

        public Avalanche(String strategy,
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
            this.strategy = strategy;
            this.ttlJitterActive = ttlJitterActive;
            this.ttlJitterPercent = ttlJitterPercent;
            this.l1CacheActive = l1CacheActive;
            this.l1RefreshIntervalSeconds = l1RefreshIntervalSeconds;
            this.l1StaleWindowSeconds = l1StaleWindowSeconds;
            this.l1StaleEntriesCached = l1StaleEntriesCached;
            this.l1RefreshInFlight = l1RefreshInFlight;
            this.dbFallbackActive = dbFallbackActive;
            this.limiterPermits = limiterPermits;
            this.limiterPeriodMs = limiterPeriodMs;
            this.limiterAcquired = limiterAcquired;
            this.limiterRejected = limiterRejected;
        }

        public String getStrategy() {
            return strategy;
        }

        public boolean isTtlJitterActive() {
            return ttlJitterActive;
        }

        public double getTtlJitterPercent() {
            return ttlJitterPercent;
        }

        public boolean isL1CacheActive() {
            return l1CacheActive;
        }

        public long getL1RefreshIntervalSeconds() {
            return l1RefreshIntervalSeconds;
        }

        public long getL1StaleWindowSeconds() {
            return l1StaleWindowSeconds;
        }

        public long getL1StaleEntriesCached() {
            return l1StaleEntriesCached;
        }

        public long getL1RefreshInFlight() {
            return l1RefreshInFlight;
        }

        public boolean isDbFallbackActive() {
            return dbFallbackActive;
        }

        public long getLimiterPermits() {
            return limiterPermits;
        }

        public long getLimiterPeriodMs() {
            return limiterPeriodMs;
        }

        public long getLimiterAcquired() {
            return limiterAcquired;
        }

        public long getLimiterRejected() {
            return limiterRejected;
        }

        public String strategy() {
            return strategy;
        }

        public boolean ttlJitterActive() {
            return ttlJitterActive;
        }

        public double ttlJitterPercent() {
            return ttlJitterPercent;
        }

        public boolean l1CacheActive() {
            return l1CacheActive;
        }

        public long l1RefreshIntervalSeconds() {
            return l1RefreshIntervalSeconds;
        }

        public long l1StaleWindowSeconds() {
            return l1StaleWindowSeconds;
        }

        public long l1StaleEntriesCached() {
            return l1StaleEntriesCached;
        }

        public long l1RefreshInFlight() {
            return l1RefreshInFlight;
        }

        public boolean dbFallbackActive() {
            return dbFallbackActive;
        }

        public long limiterPermits() {
            return limiterPermits;
        }

        public long limiterPeriodMs() {
            return limiterPeriodMs;
        }

        public long limiterAcquired() {
            return limiterAcquired;
        }

        public long limiterRejected() {
            return limiterRejected;
        }
    }

    public static final class Dataset {

        private final long startId;
        private final long endId;
        private final long productCount;
        private final long rowsInRange;
        private final long missingRows;
        private final long avalancheKeyCount;
        private final long avalancheTtlSeconds;
        private final long hotKeyProductId;

        public Dataset(long startId,
                       long endId,
                       long productCount,
                       long rowsInRange,
                       long missingRows,
                       long avalancheKeyCount,
                       long avalancheTtlSeconds,
                       long hotKeyProductId) {
            this.startId = startId;
            this.endId = endId;
            this.productCount = productCount;
            this.rowsInRange = rowsInRange;
            this.missingRows = missingRows;
            this.avalancheKeyCount = avalancheKeyCount;
            this.avalancheTtlSeconds = avalancheTtlSeconds;
            this.hotKeyProductId = hotKeyProductId;
        }

        public long getStartId() {
            return startId;
        }

        public long getEndId() {
            return endId;
        }

        public long getProductCount() {
            return productCount;
        }

        public long getRowsInRange() {
            return rowsInRange;
        }

        public long getMissingRows() {
            return missingRows;
        }

        public long getAvalancheKeyCount() {
            return avalancheKeyCount;
        }

        public long getAvalancheTtlSeconds() {
            return avalancheTtlSeconds;
        }

        public long getHotKeyProductId() {
            return hotKeyProductId;
        }

        public long startId() {
            return startId;
        }

        public long endId() {
            return endId;
        }

        public long productCount() {
            return productCount;
        }

        public long rowsInRange() {
            return rowsInRange;
        }

        public long missingRows() {
            return missingRows;
        }

        public long avalancheKeyCount() {
            return avalancheKeyCount;
        }

        public long avalancheTtlSeconds() {
            return avalancheTtlSeconds;
        }

        public long hotKeyProductId() {
            return hotKeyProductId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Dataset)) {
                return false;
            }
            Dataset that = (Dataset) other;
            return startId == that.startId
                    && endId == that.endId
                    && productCount == that.productCount
                    && rowsInRange == that.rowsInRange
                    && missingRows == that.missingRows
                    && avalancheKeyCount == that.avalancheKeyCount
                    && avalancheTtlSeconds == that.avalancheTtlSeconds
                    && hotKeyProductId == that.hotKeyProductId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(startId, endId, productCount, rowsInRange, missingRows,
                    avalancheKeyCount, avalancheTtlSeconds, hotKeyProductId);
        }
    }

    /** Live key counts; L1 is zero while the {@code cache} toggle is off. */
    public static final class Keys {

        private final boolean redisReachable;
        private final long l1ExperimentKeys;
        private final long l2ExperimentKeys;
        private final long l2AvalancheKeysAlive;
        private final boolean hotKeyCached;

        public Keys(boolean redisReachable,
                    long l1ExperimentKeys,
                    long l2ExperimentKeys,
                    long l2AvalancheKeysAlive,
                    boolean hotKeyCached) {
            this.redisReachable = redisReachable;
            this.l1ExperimentKeys = l1ExperimentKeys;
            this.l2ExperimentKeys = l2ExperimentKeys;
            this.l2AvalancheKeysAlive = l2AvalancheKeysAlive;
            this.hotKeyCached = hotKeyCached;
        }

        public boolean isRedisReachable() {
            return redisReachable;
        }

        public long getL1ExperimentKeys() {
            return l1ExperimentKeys;
        }

        public long getL2ExperimentKeys() {
            return l2ExperimentKeys;
        }

        public long getL2AvalancheKeysAlive() {
            return l2AvalancheKeysAlive;
        }

        public boolean isHotKeyCached() {
            return hotKeyCached;
        }

        public boolean redisReachable() {
            return redisReachable;
        }

        public long l1ExperimentKeys() {
            return l1ExperimentKeys;
        }

        public long l2ExperimentKeys() {
            return l2ExperimentKeys;
        }

        public long l2AvalancheKeysAlive() {
            return l2AvalancheKeysAlive;
        }

        public boolean hotKeyCached() {
            return hotKeyCached;
        }
    }

    /** Bloom filter geometry; {@code initialised} false means bloom protects nothing. */
    public static final class Bloom {

        private final String key;
        private final boolean initialised;
        private final long size;
        private final long capacity;
        private final double falseProbability;

        public Bloom(String key,
                     boolean initialised,
                     long size,
                     long capacity,
                     double falseProbability) {
            this.key = key;
            this.initialised = initialised;
            this.size = size;
            this.capacity = capacity;
            this.falseProbability = falseProbability;
        }

        public String getKey() {
            return key;
        }

        public boolean isInitialised() {
            return initialised;
        }

        public long getSize() {
            return size;
        }

        public long getCapacity() {
            return capacity;
        }

        public double getFalseProbability() {
            return falseProbability;
        }

        public String key() {
            return key;
        }

        public boolean initialised() {
            return initialised;
        }

        public long size() {
            return size;
        }

        public long capacity() {
            return capacity;
        }

        public double falseProbability() {
            return falseProbability;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Bloom)) {
                return false;
            }
            Bloom that = (Bloom) other;
            return initialised == that.initialised
                    && size == that.size
                    && capacity == that.capacity
                    && Double.compare(that.falseProbability, falseProbability) == 0
                    && Objects.equals(key, that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, initialised, size, capacity, falseProbability);
        }
    }

    /** Marker store geometry plus how many misses are currently remembered. */
    public static final class NullCache {

        private final String keyPrefix;
        private final long ttlSeconds;
        private final long jitterSeconds;
        private final long liveMarkerKeys;

        public NullCache(String keyPrefix,
                         long ttlSeconds,
                         long jitterSeconds,
                         long liveMarkerKeys) {
            this.keyPrefix = keyPrefix;
            this.ttlSeconds = ttlSeconds;
            this.jitterSeconds = jitterSeconds;
            this.liveMarkerKeys = liveMarkerKeys;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public long getJitterSeconds() {
            return jitterSeconds;
        }

        public long getLiveMarkerKeys() {
            return liveMarkerKeys;
        }

        public String keyPrefix() {
            return keyPrefix;
        }

        public long ttlSeconds() {
            return ttlSeconds;
        }

        public long jitterSeconds() {
            return jitterSeconds;
        }

        public long liveMarkerKeys() {
            return liveMarkerKeys;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof NullCache)) {
                return false;
            }
            NullCache that = (NullCache) other;
            return ttlSeconds == that.ttlSeconds
                    && jitterSeconds == that.jitterSeconds
                    && liveMarkerKeys == that.liveMarkerKeys
                    && Objects.equals(keyPrefix, that.keyPrefix);
        }

        @Override
        public int hashCode() {
            return Objects.hash(keyPrefix, ttlSeconds, jitterSeconds, liveMarkerKeys);
        }
    }
}
