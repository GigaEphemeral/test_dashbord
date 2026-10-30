package com.javalearn.lab.seckill.jdk8.lab.config;

import javax.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration of the M4-2 avalanche (雪崩) defenses — design 8.4
 * row three: {@code ttl-jitter} (±percent), {@code l1-cache} (L1-first posture
 * with the documented staleness window) and {@code db-fallback} (熔断降级到 DB +
 * 限流).
 *
 * <p>Bound from {@code app.lab.cache.avalanche.*}. A separate properties class —
 * {@link LabCacheProperties} stays the penetration/breakdown contract and is not
 * touched. Like every other lab property class it declares no Java defaults:
 * {@code application.yml} is the single source of defaults, every value is
 * environment-overridable, and {@link #validate()} fails startup on a
 * nonsensical combination instead of silently disabling a defense.</p>
 */
@ConfigurationProperties(prefix = "app.lab.cache.avalanche")
public class LabCacheAvalancheProperties {

    private final TtlJitter ttlJitter = new TtlJitter();
    private final L1Cache l1Cache = new L1Cache();
    private final DbFallback dbFallback = new DbFallback();

    @PostConstruct
    void validate() {
        if (!(ttlJitter.percent >= 0D) || ttlJitter.percent >= 100D) {
            // 100% would allow a zero TTL; a negative percentage is meaningless.
            throw new IllegalStateException("app.lab.cache.avalanche.ttl-jitter.percent"
                    + " must be in [0, 100), got: " + ttlJitter.percent);
        }

        requirePositive(l1Cache.refreshIntervalSeconds,
                "app.lab.cache.avalanche.l1-cache.refresh-interval-seconds");
        requirePositive(l1Cache.staleWindowSeconds,
                "app.lab.cache.avalanche.l1-cache.stale-window-seconds");
        if (l1Cache.staleWindowSeconds <= l1Cache.refreshIntervalSeconds) {
            // The stale window is the physical retention of the guard's L1
            // mirror; it must outlive the refresh interval or there is no
            // window in which a stale value can be served while refreshing.
            throw new IllegalStateException("app.lab.cache.avalanche.l1-cache.stale-window-seconds"
                    + " must exceed refresh-interval-seconds");
        }
        requirePositive(l1Cache.maxEntries, "app.lab.cache.avalanche.l1-cache.max-entries");
        requirePositive(l1Cache.refreshPoolSize, "app.lab.cache.avalanche.l1-cache.refresh-pool-size");
        requirePositive(l1Cache.refreshQueueCapacity,
                "app.lab.cache.avalanche.l1-cache.refresh-queue-capacity");
        requirePositive(l1Cache.keepAliveSeconds,
                "app.lab.cache.avalanche.l1-cache.keep-alive-seconds");
        requireText(l1Cache.threadNamePrefix,
                "app.lab.cache.avalanche.l1-cache.thread-name-prefix");

        requirePositive(dbFallback.permits,
                "app.lab.cache.avalanche.db-fallback.permits");
        requirePositive(dbFallback.periodMs,
                "app.lab.cache.avalanche.db-fallback.period-ms");
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

    public TtlJitter getTtlJitter() {
        return ttlJitter;
    }

    public L1Cache getL1Cache() {
        return l1Cache;
    }

    public DbFallback getDbFallback() {
        return dbFallback;
    }

    /** ±percentage applied to every product-cache L2 write while the mode is active. */
    public static class TtlJitter {

        private double percent;

        public double getPercent() {
            return percent;
        }

        public void setPercent(double percent) {
            this.percent = percent;
        }
    }

    /**
     * The L1-first posture: a bounded per-instance mirror of the last good
     * values. A value is fresh for {@code refresh-interval-seconds}; after that
     * it is served stale (and one async refresh rebuilds it) up to
     * {@code stale-window-seconds}, which is also the documented staleness cap.
     */
    public static class L1Cache {

        private long refreshIntervalSeconds;
        private long staleWindowSeconds;
        private long maxEntries;
        private int refreshPoolSize;
        private int refreshQueueCapacity;
        private long keepAliveSeconds;
        private String threadNamePrefix;

        public long getRefreshIntervalSeconds() {
            return refreshIntervalSeconds;
        }

        public void setRefreshIntervalSeconds(long refreshIntervalSeconds) {
            this.refreshIntervalSeconds = refreshIntervalSeconds;
        }

        public long getStaleWindowSeconds() {
            return staleWindowSeconds;
        }

        public void setStaleWindowSeconds(long staleWindowSeconds) {
            this.staleWindowSeconds = staleWindowSeconds;
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

    /** The bounded DB-fallback budget of the circuit-breaking avalanche mode. */
    public static class DbFallback {

        private long permits;
        private long periodMs;

        public long getPermits() {
            return permits;
        }

        public void setPermits(long permits) {
            this.permits = permits;
        }

        public long getPeriodMs() {
            return periodMs;
        }

        public void setPeriodMs(long periodMs) {
            this.periodMs = periodMs;
        }
    }
}
