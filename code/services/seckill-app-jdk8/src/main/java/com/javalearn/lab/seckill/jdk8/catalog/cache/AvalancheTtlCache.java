package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.javalearn.lab.seckill.jdk8.lab.toggle.CacheDefenseMode;
import com.javalearn.lab.seckill.jdk8.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * The write-side half of {@code ttl-jitter} (design 8.4 雪崩 row): a thin
 * {@link Cache} decorator around the product cache that, while the mode is
 * active, rewrites the TTL the two-level cache just wrote with a jittered one.
 *
 * <p>Why a decorator instead of touching {@link TwoLevelCache}: the cache's L2
 * write TTL is a fixed constant owned by that class, and the jitter must be a
 * runtime-switchable defense that leaves {@code off} byte-for-byte unchanged.
 * The decorator is resolved only for the {@code @Cacheable} product read (see
 * {@link AvalancheCacheResolver}), so every other cache user — including the
 * existing evict/clear paths — keeps talking to the raw cache.</p>
 *
 * <p>The read of the just-written TTL and the rewrite are both best-effort:
 * Redis being down already degrades the write inside {@link TwoLevelCache}, and
 * the jitter must never turn a working read into a failed one. Outcomes are
 * counted as {@code cache_avalanche_ttl_jitter{cache,result=applied|skipped|error}}.</p>
 */
final class AvalancheTtlCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(AvalancheTtlCache.class);

    private final Cache delegate;
    private final CacheDefenseModeService modeService;
    private final TtlJitter jitter;
    private final L2TtlAdjuster adjuster;
    private final Counter applied;
    private final Counter skipped;
    private final Counter errors;

    AvalancheTtlCache(Cache delegate,
                      CacheDefenseModeService modeService,
                      TtlJitter jitter,
                      L2TtlAdjuster adjuster,
                      MeterRegistry meterRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.modeService = Objects.requireNonNull(modeService, "modeService");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.adjuster = Objects.requireNonNull(adjuster, "adjuster");
        this.applied = counter("applied", delegate.getName(), meterRegistry);
        this.skipped = counter("skipped", delegate.getName(), meterRegistry);
        this.errors = counter("error", delegate.getName(), meterRegistry);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        return delegate.get(key);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return delegate.get(key, type);
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        return delegate.get(key, valueLoader);
    }

    @Override
    public void put(Object key, Object value) {
        delegate.put(key, value);
        if (modeService.current() != CacheDefenseMode.TTL_JITTER) {
            return;
        }
        try {
            Duration remaining = adjuster.remainingTtl(key);
            if (remaining == null) {
                // No TTL to spread (cache toggle off, non-product key, entry
                // already gone): nothing to do, and nothing to alarm about.
                skipped.increment();
                return;
            }
            adjuster.rewrite(key, jitter.jitter(remaining));
            applied.increment();
        } catch (RuntimeException ex) {
            errors.increment();
            log.warn("TTL jitter could not be applied to cache '{}' key '{}': {}",
                    delegate.getName(), key, ex.toString());
        }
    }

    @Override
    public void evict(Object key) {
        delegate.evict(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    private static Counter counter(String result, String cache, MeterRegistry registry) {
        return Counter.builder("cache_avalanche_ttl_jitter")
                .tag("cache", cache).tag("result", result).register(registry);
    }
}
