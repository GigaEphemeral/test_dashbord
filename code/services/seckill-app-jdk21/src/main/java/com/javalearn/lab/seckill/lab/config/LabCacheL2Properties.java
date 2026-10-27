package com.javalearn.lab.seckill.lab.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration of the Redis L2 inside {@code TwoLevelCache} —
 * the values that used to be compile-time constants of that class
 * (Stage2 B0-04): the write TTL, the {@code cache:} key prefix and the SCAN
 * COUNT hint of {@code clear()}.
 *
 * <p>Bound from {@code app.lab.cache.l2.*}. Like every other lab property
 * class (see {@link LabCacheProperties}, {@link LabCacheAvalancheProperties}) it
 * declares no Java defaults: {@code application.yml} is the single source of
 * defaults, every value is environment-overridable, and {@link #validate()}
 * fails startup on a missing or nonsensical value instead of hiding it.</p>
 *
 * <p>The key prefix is the L2 <em>layout</em> contract, so the experiment seeder,
 * the state reader, the preheat service and the TTL-jitter adjuster all resolve
 * it through {@link com.javalearn.lab.seckill.catalog.cache.TwoLevelCacheSettings}
 * rather than repeating the literal — one change of the value moves every
 * reader and writer together.</p>
 */
@ConfigurationProperties(prefix = "app.lab.cache.l2")
public class LabCacheL2Properties {

    /** Redis write TTL of every L2 entry ({@code SET key json EX ttl-seconds}). */
    private long ttlSeconds;
    /** Prefix of every L2 key; the layout is {@code <keyPrefix><cacheName>:<key>}. */
    private String keyPrefix;
    /** Redis SCAN COUNT hint used when {@code clear()} deletes the cache's keys. */
    private long clearScanCount;

    @PostConstruct
    void validate() {
        if (ttlSeconds <= 0L) {
            throw new IllegalStateException("Configuration must be positive: "
                    + "app.lab.cache.l2.ttl-seconds (" + ttlSeconds + ")");
        }
        if (keyPrefix == null || keyPrefix.trim().isEmpty()) {
            throw new IllegalStateException("Missing required configuration: "
                    + "app.lab.cache.l2.key-prefix");
        }
        if (clearScanCount <= 0L) {
            throw new IllegalStateException("Configuration must be positive: "
                    + "app.lab.cache.l2.clear-scan-count (" + clearScanCount + ")");
        }
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public long getClearScanCount() {
        return clearScanCount;
    }

    public void setClearScanCount(long clearScanCount) {
        this.clearScanCount = clearScanCount;
    }
}
