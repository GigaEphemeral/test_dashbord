package com.javalearn.lab.seckill.lab.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

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
    private final Penetration penetration = new Penetration();

    @PostConstruct
    void validate() {
        requirePositive(seed.productCount, "app.lab.cache.seed.product-count");
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

    public Penetration getPenetration() {
        return penetration;
    }

    /** Size and id range of the generated experiment dataset. */
    public static class Seed {

        private long productCount;
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
}
