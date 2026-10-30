package com.javalearn.lab.seckill.jdk8.catalog.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Redis marker store for "this product id does not exist" (cache penetration).
 *
 * <p>A miss is recorded under {@code <keyPrefix><id>} with
 * {@code ttl + jitter} seconds. The TTL is short because a marker is a promise
 * about absent data: a product inserted later must become visible quickly. The
 * jitter is what keeps a burst of misses on many ids from expiring in the same
 * second and re-creating the very stampede (and avalanche alignment) the lab is
 * demonstrating.</p>
 *
 * <p>Redis is best-effort, exactly like the L2 cache: a marker read or write
 * failure is counted as {@code cache_null_cache_errors} and the request falls
 * through to the database. A missing Redis must never turn a 404 into a 500.</p>
 */
public final class NullValueMarkerStore {

    static final String MARKER_VALUE = "NULL";

    private static final Logger log = LoggerFactory.getLogger(NullValueMarkerStore.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Duration baseTtl;
    private final Duration jitter;
    private final LongSupplier jitterSeconds;

    private final Counter markerHits;
    private final Counter markerMisses;
    private final Counter markerWrites;
    private final Counter markerErrors;

    public NullValueMarkerStore(StringRedisTemplate redisTemplate,
                                String keyPrefix,
                                Duration baseTtl,
                                Duration jitter,
                                LongSupplier jitterSeconds,
                                MeterRegistry meterRegistry) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.baseTtl = requireNonNegative(baseTtl, "null-cache ttl");
        this.jitter = requireNonNegative(jitter, "null-cache jitter");
        this.jitterSeconds = Objects.requireNonNull(jitterSeconds, "jitterSeconds");
        String cache = TwoLevelCacheManager.PRODUCT_CACHE;
        this.markerHits = Counter.builder("cache_null_cache_requests")
                .tag("cache", cache).tag("result", "hit")
                .register(meterRegistry);
        this.markerMisses = Counter.builder("cache_null_cache_requests")
                .tag("cache", cache).tag("result", "miss")
                .register(meterRegistry);
        this.markerWrites = Counter.builder("cache_null_cache_writes")
                .tag("cache", cache)
                .register(meterRegistry);
        this.markerErrors = Counter.builder("cache_null_cache_errors")
                .tag("cache", cache)
                .register(meterRegistry);
    }

    /**
     * Production jitter source: a whole second drawn uniformly from
     * {@code [0, jitter]} so the configured TTL is a lower bound.
     */
    public static LongSupplier randomJitterSeconds(Duration jitter) {
        long bound = requireNonNegative(jitter, "null-cache jitter").getSeconds();
        if (bound <= 0L) {
            return () -> 0L;
        }
        return () -> ThreadLocalRandom.current().nextLong(bound + 1L);
    }

    /**
     * @return {@code true} when a marker says this id is absent; a Redis failure
     *         counts as no marker so the caller still queries the database
     */
    public boolean isMarkedAbsent(long id) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(redisKey(id));
        } catch (RuntimeException ex) {
            countError("read", id, ex);
            return false;
        }
        if (value == null) {
            markerMisses.increment();
            return false;
        }
        markerHits.increment();
        return true;
    }

    /**
     * Records a miss. Best-effort: a Redis failure is logged and counted, the
     * caller still returns its normal 404.
     */
    public void markAbsent(long id) {
        try {
            redisTemplate.opsForValue().set(redisKey(id), MARKER_VALUE, entryTtl());
            markerWrites.increment();
        } catch (RuntimeException ex) {
            countError("write", id, ex);
        }
    }

    /** The TTL a marker written now would get: {@code baseTtl + drawn jitter}. */
    public Duration entryTtl() {
        return baseTtl.plusSeconds(jitterSeconds.getAsLong());
    }

    Duration baseTtl() {
        return baseTtl;
    }

    Duration jitter() {
        return jitter;
    }

    String redisKey(long id) {
        return keyPrefix + id;
    }

    /** Prefix of the marker keys, for the read-only state view. */
    public String keyPrefix() {
        return keyPrefix;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException("Negative " + name + ": " + value);
        }
        return value;
    }

    private void countError(String operation, long id, RuntimeException ex) {
        markerErrors.increment();
        log.warn("Redis null-cache marker {} failed for product '{}'; falling through to source: {}",
                operation, id, ex.toString());
    }
}
