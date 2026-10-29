package com.javalearn.lab.seckill.lab.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Externalized configuration for the cache experiment dataset and the
 * penetration defense.
 *
 * <p>Bound from {@code app.lab.cache.*}. Like {@code SeckillProperties} this
 * class declares no Java defaults: {@code application.yml} is the single source
 * of defaults, every value is environment-overridable and {@link #validate()}
 * fails startup on a missing or nonsensical value instead of hiding it.</p>
 */
@ConfigurationProperties(prefix = "app.lab.cache")
public class LabCacheProperties {

    private final Seed seed = new Seed();
    private final State state = new State();
    private final Keys keys = new Keys();
    private final Penetration penetration = new Penetration();
    private final Breakdown breakdown = new Breakdown();

    @PostConstruct
    void validate() {
        requirePositive(seed.productCount, "app.lab.cache.seed.product-count");
        requirePositive(seed.maxRequestCount, "app.lab.cache.seed.max-request-count");
        if (seed.maxRequestCount < seed.productCount) {
            throw new IllegalStateException("app.lab.cache.seed.max-request-count must be at least"
                    + " app.lab.cache.seed.product-count");
        }
        requirePositive(seed.startId, "app.lab.cache.seed.start-id");
        requirePositive(seed.writeBatchSize, "app.lab.cache.seed.write-batch-size");
        requireNonNegative(seed.protectedMaxId, "app.lab.cache.seed.protected-max-id");
        if (seed.startId <= seed.protectedMaxId) {
            throw new IllegalStateException("app.lab.cache.seed.start-id must be greater than"
                    + " app.lab.cache.seed.protected-max-id so the demo baseline stays untouched");
        }
        if (seed.startId + seed.productCount - 1 < seed.startId) {
            throw new IllegalStateException("app.lab.cache.seed id range overflows: "
                    + seed.startId + " + " + seed.productCount);
        }
        requireNonNegative(seed.avalancheKeyCount, "app.lab.cache.seed.avalanche-key-count");
        if (seed.avalancheKeyCount > seed.productCount) {
            throw new IllegalStateException("app.lab.cache.seed.avalanche-key-count must not exceed"
                    + " app.lab.cache.seed.product-count");
        }
        requirePositive(seed.avalancheTtlSeconds, "app.lab.cache.seed.avalanche-ttl-seconds");
        requirePositive(seed.hotKeyTtlSeconds, "app.lab.cache.seed.hot-key-ttl-seconds");
        if (seed.hotKeyIndex < 0L || seed.hotKeyIndex >= seed.productCount) {
            throw new IllegalStateException("app.lab.cache.seed.hot-key-index must address"
                    + " a product inside the experiment range");
        }
        if (seed.hotKeyIndex < seed.avalancheKeyCount) {
            // The hot key is warmed with its own TTL; letting it land inside the
            // avalanche range would overwrite one of the identically-expiring
            // keys and quietly break the M4-2/M4-3 avalanche setup.
            throw new IllegalStateException("app.lab.cache.seed.hot-key-index must not address"
                    + " a key inside the avalanche range [0, avalanche-key-count)");
        }
        requireText(seed.namePrefix, "app.lab.cache.seed.name-prefix");
        requireText(seed.status, "app.lab.cache.seed.status");
        requirePositive(state.scanCount, "app.lab.cache.state.scan-count");
        if (seed.price == null || seed.price.signum() < 0) {
            throw new IllegalStateException("app.lab.cache.seed.price must be a non-negative number");
        }

        requirePositive(keys.scanBatches, "app.lab.cache.keys.scan-batches");
        requireText(keys.defaultPrefix, "app.lab.cache.keys.default-prefix");
        if (keys.allowedPrefixes.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required configuration: app.lab.cache.keys.allowed-prefixes");
        }
        for (String allowed : keys.allowedPrefixes) {
            requireText(allowed, "app.lab.cache.keys.allowed-prefixes");
        }
        if (!keys.allowedPrefixes.contains(keys.defaultPrefix)) {
            throw new IllegalStateException("app.lab.cache.keys.default-prefix must be part of"
                    + " app.lab.cache.keys.allowed-prefixes");
        }
        requirePositive(keys.defaultLimit, "app.lab.cache.keys.default-limit");
        requirePositive(keys.maxLimit, "app.lab.cache.keys.max-limit");
        if (keys.defaultLimit > keys.maxLimit) {
            throw new IllegalStateException("app.lab.cache.keys.default-limit must not exceed"
                    + " app.lab.cache.keys.max-limit");
        }
        requirePositive(keys.scanCount, "app.lab.cache.keys.scan-count");
        requirePositive(keys.valueMaxBytes, "app.lab.cache.keys.value-max-bytes");
        requirePositive(keys.resetScanCount, "app.lab.cache.keys.reset-scan-count");
        requirePositive(keys.resetMaxKeys, "app.lab.cache.keys.reset-max-keys");

        if (penetration.mode == null || penetration.mode.trim().isEmpty()) {
            throw new IllegalStateException("Missing required configuration:"
                    + " app.lab.cache.penetration.mode");
        }
        requireText(penetration.getNullCache().getKeyPrefix(),
                "app.lab.cache.penetration.null-cache.key-prefix");
        requirePositive(penetration.getNullCache().getTtlSeconds(),
                "app.lab.cache.penetration.null-cache.ttl-seconds");
        requireNonNegative(penetration.getNullCache().getJitterSeconds(),
                "app.lab.cache.penetration.null-cache.jitter-seconds");
        requireText(penetration.getBloom().getKey(), "app.lab.cache.penetration.bloom.key");
        requirePositive(penetration.getBloom().getExpectedInsertions(),
                "app.lab.cache.penetration.bloom.expected-insertions");
        double fpp = penetration.getBloom().getFalseProbability();
        if (fpp <= 0D || fpp >= 1D) {
            throw new IllegalStateException("app.lab.cache.penetration.bloom.false-probability"
                    + " must be between 0 and 1 exclusive");
        }

        LabCacheProperties.Mutex mutex = breakdown.getMutex();
        requireText(mutex.getKeyPrefix(), "app.lab.cache.breakdown.mutex.key-prefix");
        requirePositive(mutex.getWaitMs(), "app.lab.cache.breakdown.mutex.wait-ms");
        requirePositive(mutex.getLeaseMs(), "app.lab.cache.breakdown.mutex.lease-ms");
        if (mutex.getLeaseMs() < mutex.getWaitMs()) {
            // A lease shorter than the wait would let a waiter "acquire" a lock
            // whose holder is already gone mid-rebuild, defeating the mutex.
            throw new IllegalStateException("app.lab.cache.breakdown.mutex.lease-ms must be"
                    + " at least app.lab.cache.breakdown.mutex.wait-ms");
        }
        if (!"db".equals(mutex.getOnLockTimeout()) && !"stale".equals(mutex.getOnLockTimeout())) {
            throw new IllegalStateException("app.lab.cache.breakdown.mutex.on-lock-timeout"
                    + " must be db or stale, got: " + mutex.getOnLockTimeout());
        }
        LabCacheProperties.LogicalExpire logical = breakdown.getLogicalExpire();
        requirePositive(logical.getLogicalTtlSeconds(),
                "app.lab.cache.breakdown.logical-expire.logical-ttl-seconds");
        requirePositive(logical.getRetentionSeconds(),
                "app.lab.cache.breakdown.logical-expire.retention-seconds");
        if (logical.getRetentionSeconds() <= logical.getLogicalTtlSeconds()) {
            // The physical window must outlive the logical one, otherwise there
            // is no stale value left to serve as the fallback and the whole
            // strategy silently degrades to mutex-less DB stampede.
            throw new IllegalStateException("app.lab.cache.breakdown.logical-expire"
                    + ".retention-seconds must exceed logical-ttl-seconds");
        }
        if (logical.getRetentionSeconds() <= seed.getHotKeyTtlSeconds()) {
            // The guard only sees traffic once the L2 hot key expires. If the
            // stale retention window is shorter than the hot-key TTL, every
            // breakdown burst finds an EMPTY stale store: logical-expire would
            // silently behave like `off` (cold stampede, no stale-serve metric).
            throw new IllegalStateException("app.lab.cache.breakdown.logical-expire"
                    + ".retention-seconds must exceed app.lab.cache.seed.hot-key-ttl-seconds ("
                    + seed.getHotKeyTtlSeconds() + ") or the stale fallback is evicted"
                    + " before the hot key expires");
        }
        requirePositive(logical.getMaxEntries(),
                "app.lab.cache.breakdown.logical-expire.max-entries");
        requirePositive(logical.getRefreshPoolSize(),
                "app.lab.cache.breakdown.logical-expire.refresh-pool-size");
        requirePositive(logical.getRefreshQueueCapacity(),
                "app.lab.cache.breakdown.logical-expire.refresh-queue-capacity");
        requirePositive(logical.getKeepAliveSeconds(),
                "app.lab.cache.breakdown.logical-expire.keep-alive-seconds");
        requireText(logical.getThreadNamePrefix(),
                "app.lab.cache.breakdown.logical-expire.thread-name-prefix");
    }

    private static void requireText(String value, String key) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing required configuration: " + key);
        }
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0L) {
            throw new IllegalStateException("Configuration must be positive: " + key);
        }
    }

    private static void requireNonNegative(long value, String key) {
        if (value < 0L) {
            throw new IllegalStateException("Configuration must not be negative: " + key);
        }
    }

    public Seed getSeed() {
        return seed;
    }

    public State getState() {
        return state;
    }

    public Keys getKeys() {
        return keys;
    }

    public Penetration getPenetration() {
        return penetration;
    }

    public Breakdown getBreakdown() {
        return breakdown;
    }

    /** Size and id range of the generated experiment dataset. */
    public static class Seed {

        private long productCount;
        /** Upper bound of one POST /lab/cache/seed {"count":N} request. */
        private long maxRequestCount = 10000L;
        private long startId;
        private long protectedMaxId;
        private long avalancheKeyCount;
        private long avalancheTtlSeconds;
        private long hotKeyIndex;
        private long hotKeyTtlSeconds;
        private int writeBatchSize;
        private String namePrefix;
        private String status;
        private BigDecimal price;

        public long getProductCount() {
            return productCount;
        }

        public long getMaxRequestCount() {
            return maxRequestCount;
        }

        public void setMaxRequestCount(long maxRequestCount) {
            this.maxRequestCount = maxRequestCount;
        }

        public void setProductCount(long productCount) {
            this.productCount = productCount;
        }

        public long getStartId() {
            return startId;
        }

        public void setStartId(long startId) {
            this.startId = startId;
        }

        public long getProtectedMaxId() {
            return protectedMaxId;
        }

        public void setProtectedMaxId(long protectedMaxId) {
            this.protectedMaxId = protectedMaxId;
        }

        public long getAvalancheKeyCount() {
            return avalancheKeyCount;
        }

        public void setAvalancheKeyCount(long avalancheKeyCount) {
            this.avalancheKeyCount = avalancheKeyCount;
        }

        public long getAvalancheTtlSeconds() {
            return avalancheTtlSeconds;
        }

        public void setAvalancheTtlSeconds(long avalancheTtlSeconds) {
            this.avalancheTtlSeconds = avalancheTtlSeconds;
        }

        public long getHotKeyIndex() {
            return hotKeyIndex;
        }

        public void setHotKeyIndex(long hotKeyIndex) {
            this.hotKeyIndex = hotKeyIndex;
        }

        public long getHotKeyTtlSeconds() {
            return hotKeyTtlSeconds;
        }

        public void setHotKeyTtlSeconds(long hotKeyTtlSeconds) {
            this.hotKeyTtlSeconds = hotKeyTtlSeconds;
        }

        public int getWriteBatchSize() {
            return writeBatchSize;
        }

        public void setWriteBatchSize(int writeBatchSize) {
            this.writeBatchSize = writeBatchSize;
        }

        public String getNamePrefix() {
            return namePrefix;
        }

        public void setNamePrefix(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }
    }

    /** Read-only observability of the experiment; nothing here changes behaviour. */
    public static class State {

        /** Redis SCAN COUNT hint used when counting the live experiment keys. */
        private long scanCount;

        public long getScanCount() {
            return scanCount;
        }

        public void setScanCount(long scanCount) {
            this.scanCount = scanCount;
        }
    }

    /**
     * The SCAN-only key browser ({@code GET /lab/cache/keys}) and the
     * experiment reset ({@code POST /lab/cache/reset}).
     *
     * <p>{@code allowed-prefixes} is a closed allow-list: the endpoint must not
     * become a generic keyspace dump, so any prefix outside this list is a 400.
     * {@code max-limit} is the hard page cap, {@code scan-count} the Redis
     * COUNT hint and {@code reset-max-keys} the per-call deletion bound that
     * keeps reset fast on a 10k-key dataset.</p>
     */
    public static class Keys {

        private String defaultPrefix;
        private List<String> allowedPrefixes = new ArrayList<>();
        private int defaultLimit;
        private int maxLimit;
        private long scanCount;
        /** Bounded SCAN batches per page, so a sparse prefix still fills the page. */
        private int scanBatches = 20;
        private int valueMaxBytes;
        private long resetScanCount;
        private long resetMaxKeys;

        public String getDefaultPrefix() {
            return defaultPrefix;
        }

        public void setDefaultPrefix(String defaultPrefix) {
            this.defaultPrefix = defaultPrefix;
        }

        public List<String> getAllowedPrefixes() {
            return allowedPrefixes;
        }

        public void setAllowedPrefixes(List<String> allowedPrefixes) {
            this.allowedPrefixes = allowedPrefixes;
        }

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getMaxLimit() {
            return maxLimit;
        }

        public void setMaxLimit(int maxLimit) {
            this.maxLimit = maxLimit;
        }

        public long getScanCount() {
            return scanCount;
        }

        public void setScanCount(long scanCount) {
            this.scanCount = scanCount;
        }

        public int getScanBatches() {
            return scanBatches;
        }

        public void setScanBatches(int scanBatches) {
            this.scanBatches = scanBatches;
        }

        public int getValueMaxBytes() {
            return valueMaxBytes;
        }

        public void setValueMaxBytes(int valueMaxBytes) {
            this.valueMaxBytes = valueMaxBytes;
        }

        public long getResetScanCount() {
            return resetScanCount;
        }

        public void setResetScanCount(long resetScanCount) {
            this.resetScanCount = resetScanCount;
        }

        public long getResetMaxKeys() {
            return resetMaxKeys;
        }

        public void setResetMaxKeys(long resetMaxKeys) {
            this.resetMaxKeys = resetMaxKeys;
        }
    }

    /** The runtime-switchable penetration strategy and its parameters. */
    public static class Penetration {

        /** Startup seed of {@link com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService}. */
        private String mode;
        private final NullCache nullCache = new NullCache();
        private final Bloom bloom = new Bloom();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public NullCache getNullCache() {
            return nullCache;
        }

        public Bloom getBloom() {
            return bloom;
        }
    }

    /** Short-TTL + jittered miss markers. */
    public static class NullCache {

        private String keyPrefix;
        private long ttlSeconds;
        private long jitterSeconds;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public long getJitterSeconds() {
            return jitterSeconds;
        }

        public void setJitterSeconds(long jitterSeconds) {
            this.jitterSeconds = jitterSeconds;
        }
    }

    /** Redisson bloom filter over the existing product ids. */
    public static class Bloom {

        private String key;
        private long expectedInsertions;
        private double falseProbability;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public long getExpectedInsertions() {
            return expectedInsertions;
        }

        public void setExpectedInsertions(long expectedInsertions) {
            this.expectedInsertions = expectedInsertions;
        }

        public double getFalseProbability() {
            return falseProbability;
        }

        public void setFalseProbability(double falseProbability) {
            this.falseProbability = falseProbability;
        }
    }

    /** The runtime-switchable hot-key breakdown (击穿) strategies and their parameters. */
    public static class Breakdown {

        private final Mutex mutex = new Mutex();
        private final LogicalExpire logicalExpire = new LogicalExpire();

        public Mutex getMutex() {
            return mutex;
        }

        public LogicalExpire getLogicalExpire() {
            return logicalExpire;
        }
    }

    /** Redisson lock + double-check: one thread rebuilds the expired hot key. */
    public static class Mutex {

        private String keyPrefix;
        private long waitMs;
        private long leaseMs;
        /** What a waiter does after the lock wait times out: {@code db} or {@code stale}. */
        private String onLockTimeout;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public long getWaitMs() {
            return waitMs;
        }

        public void setWaitMs(long waitMs) {
            this.waitMs = waitMs;
        }

        public long getLeaseMs() {
            return leaseMs;
        }

        public void setLeaseMs(long leaseMs) {
            this.leaseMs = leaseMs;
        }

        public String getOnLockTimeout() {
            return onLockTimeout;
        }

        public void setOnLockTimeout(String onLockTimeout) {
            this.onLockTimeout = onLockTimeout;
        }
    }

    /** Logical expiry inside a longer physical retention + bounded async refresh. */
    public static class LogicalExpire {

        /** How long a value is considered fresh before it may be served stale. */
        private long logicalTtlSeconds;
        /** Physical lifetime of the stored value; must exceed the logical TTL. */
        private long retentionSeconds;
        private long maxEntries;
        private int refreshPoolSize;
        private int refreshQueueCapacity;
        /** Idle refresh-thread lifetime; core threads time out too (bounded pool). */
        private long keepAliveSeconds;
        private String threadNamePrefix;

        public long getLogicalTtlSeconds() {
            return logicalTtlSeconds;
        }

        public void setLogicalTtlSeconds(long logicalTtlSeconds) {
            this.logicalTtlSeconds = logicalTtlSeconds;
        }

        public long getRetentionSeconds() {
            return retentionSeconds;
        }

        public void setRetentionSeconds(long retentionSeconds) {
            this.retentionSeconds = retentionSeconds;
        }

        public long getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(long maxEntries) {
            this.maxEntries = maxEntries;
        }

        public int getRefreshPoolSize() {
            return refreshPoolSize;
        }

        public void setRefreshPoolSize(int refreshPoolSize) {
            this.refreshPoolSize = refreshPoolSize;
        }

        public int getRefreshQueueCapacity() {
            return refreshQueueCapacity;
        }

        public void setRefreshQueueCapacity(int refreshQueueCapacity) {
            this.refreshQueueCapacity = refreshQueueCapacity;
        }

        public long getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(long keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }
    }
}
