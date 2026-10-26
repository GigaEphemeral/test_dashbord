package com.javalearn.lab.seckill.lab.cache.preheat;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the Caffeine preheat lab (design 8.4, M4-3).
 *
 * <p>Bound from {@code app.lab.cache.preheat.*}. Mirrors
 * {@code LabCacheProperties}: the class declares no Java defaults —
 * {@code application.yml} is the single source of defaults, every capacity,
 * interval, TTL, chunk size and pool size is {@code ${ENV:default}}
 * overridable, and {@link #validate()} fails startup on a missing or
 * nonsensical value instead of hiding it.</p>
 *
 * <p>{@code enabled} gates the automatic startup and periodic preheat runs.
 * Hot-key tracking and publishing stay active even when the automatic runs are
 * disabled: the shared Redis hot list is then still observable and the manual
 * {@code POST /lab/cache/preheat} endpoint remains usable.</p>
 */
@ConfigurationProperties(prefix = "app.lab.cache.preheat")
public class LabCachePreheatProperties {

    /** Startup + periodic preheat gate; the manual trigger is always allowed. */
    private boolean enabled;
    /** How many hot products to publish and to warm at most (design 8.4: 200). */
    private long topN;
    /** Shared Redis ZSet holding the cross-instance hot list. */
    private String hotListKey;

    private final Tracker tracker = new Tracker();
    private final Load load = new Load();
    private final L1 l1 = new L1();

    @PostConstruct
    void validate() {
        requirePositive(topN, "app.lab.cache.preheat.top-n");
        requireText(hotListKey, "app.lab.cache.preheat.hot-list-key");

        requirePositive(tracker.capacity, "app.lab.cache.preheat.tracker.capacity");
        requirePositive(tracker.decayIntervalSeconds,
                "app.lab.cache.preheat.tracker.decay-interval-seconds");
        requirePositive(tracker.flushIntervalSeconds,
                "app.lab.cache.preheat.tracker.flush-interval-seconds");
        if (topN > tracker.capacity) {
            // Publishing a Top-N wider than the tracker can hold would just be
            // a slower way of publishing the whole counter store.
            throw new IllegalStateException("app.lab.cache.preheat.top-n must not exceed"
                    + " app.lab.cache.preheat.tracker.capacity");
        }

        requirePositive(load.chunkSize, "app.lab.cache.preheat.load.chunk-size");
        requireNonNegative(load.chunkPauseMs, "app.lab.cache.preheat.load.chunk-pause-ms");
        requireNonNegative(load.startupDelayMs, "app.lab.cache.preheat.load.startup-delay-ms");
        requirePositive(load.periodicIntervalSeconds,
                "app.lab.cache.preheat.load.periodic-interval-seconds");
        requirePositive(load.schedulerPoolSize,
                "app.lab.cache.preheat.load.scheduler-pool-size");
        requireText(load.threadNamePrefix, "app.lab.cache.preheat.load.thread-name-prefix");

        requirePositive(l1.maximumSize, "app.lab.cache.preheat.l1.maximum-size");
        requirePositive(l1.refreshAfterWriteSeconds,
                "app.lab.cache.preheat.l1.refresh-after-write-seconds");
        requirePositive(l1.expireAfterWriteSeconds,
                "app.lab.cache.preheat.l1.expire-after-write-seconds");
        if (l1.expireAfterWriteSeconds <= l1.refreshAfterWriteSeconds) {
            // refreshAfterWrite only fires while the entry is still alive; an
            // expiry at or below the refresh interval would silently disable it.
            throw new IllegalStateException("app.lab.cache.preheat.l1.expire-after-write-seconds"
                    + " must exceed refresh-after-write-seconds");
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTopN() {
        return topN;
    }

    public void setTopN(long topN) {
        this.topN = topN;
    }

    public String getHotListKey() {
        return hotListKey;
    }

    public void setHotListKey(String hotListKey) {
        this.hotListKey = hotListKey;
    }

    public Tracker getTracker() {
        return tracker;
    }

    public Load getLoad() {
        return load;
    }

    public L1 getL1() {
        return l1;
    }

    /** Per-instance hot-key counter geometry. */
    public static class Tracker {

        /** Caffeine cap on tracked product ids (design 8.4: 100k). */
        private long capacity;
        /** Counters halve once per interval (design 8.4: 30s). */
        private long decayIntervalSeconds;
        /** How often the Top-N is published to the shared ZSet. */
        private long flushIntervalSeconds;

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public long getDecayIntervalSeconds() {
            return decayIntervalSeconds;
        }

        public void setDecayIntervalSeconds(long decayIntervalSeconds) {
            this.decayIntervalSeconds = decayIntervalSeconds;
        }

        public long getFlushIntervalSeconds() {
            return flushIntervalSeconds;
        }

        public void setFlushIntervalSeconds(long flushIntervalSeconds) {
            this.flushIntervalSeconds = flushIntervalSeconds;
        }
    }

    /** Rate limiter and scheduling of the preheat runs. */
    public static class Load {

        /** How many products are read from L2 before the rate-limit pause. */
        private long chunkSize;
        /** Pause between chunks, so preheat never turns into a Redis/DB burst. */
        private long chunkPauseMs;
        /** Delay after ApplicationReadyEvent before the async startup run. */
        private long startupDelayMs;
        /** Incremental reheat period (design 8.4: 5 minutes). */
        private long periodicIntervalSeconds;
        /** Worker count of the daemon scheduler that runs flush/preheat/periodic. */
        private long schedulerPoolSize;
        private String threadNamePrefix;

        public long getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(long chunkSize) {
            this.chunkSize = chunkSize;
        }

        public long getChunkPauseMs() {
            return chunkPauseMs;
        }

        public void setChunkPauseMs(long chunkPauseMs) {
            this.chunkPauseMs = chunkPauseMs;
        }

        public long getStartupDelayMs() {
            return startupDelayMs;
        }

        public void setStartupDelayMs(long startupDelayMs) {
            this.startupDelayMs = startupDelayMs;
        }

        public long getPeriodicIntervalSeconds() {
            return periodicIntervalSeconds;
        }

        public void setPeriodicIntervalSeconds(long periodicIntervalSeconds) {
            this.periodicIntervalSeconds = periodicIntervalSeconds;
        }

        public long getSchedulerPoolSize() {
            return schedulerPoolSize;
        }

        public void setSchedulerPoolSize(long schedulerPoolSize) {
            this.schedulerPoolSize = schedulerPoolSize;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }
    }

    /** Externalized Caffeine L1 geometry (the historical hardcoding, now bound). */
    public static class L1 {

        private long maximumSize;
        private long refreshAfterWriteSeconds;
        private long expireAfterWriteSeconds;

        public long getMaximumSize() {
            return maximumSize;
        }

        public void setMaximumSize(long maximumSize) {
            this.maximumSize = maximumSize;
        }

        public long getRefreshAfterWriteSeconds() {
            return refreshAfterWriteSeconds;
        }

        public void setRefreshAfterWriteSeconds(long refreshAfterWriteSeconds) {
            this.refreshAfterWriteSeconds = refreshAfterWriteSeconds;
        }

        public long getExpireAfterWriteSeconds() {
            return expireAfterWriteSeconds;
        }

        public void setExpireAfterWriteSeconds(long expireAfterWriteSeconds) {
            this.expireAfterWriteSeconds = expireAfterWriteSeconds;
        }
    }
}
