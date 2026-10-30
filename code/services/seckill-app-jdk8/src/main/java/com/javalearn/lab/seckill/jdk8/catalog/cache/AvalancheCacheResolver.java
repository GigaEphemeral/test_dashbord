package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.javalearn.lab.seckill.jdk8.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the product cache as an {@link AvalancheTtlCache} for the
 * {@code @Cacheable} read path only, which is how {@code ttl-jitter} hooks the
 * L2 write without changing {@link TwoLevelCache} or the {@link CacheManager}.
 *
 * <p>The raw cache is still what every other consumer sees (evict/clear in the
 * lab state, the breakdown probe/writer, tests); the decorator exists exactly
 * around the cache operation that writes products. While the cache toggle is
 * off the manager hands out a {@link NoOpCache}: it is returned unwrapped, so a
 * bypassed cache cannot trigger a single extra Redis call.</p>
 */
public final class AvalancheCacheResolver implements CacheResolver {

    private final CacheManager cacheManager;
    private final CacheDefenseModeService modeService;
    private final TtlJitter jitter;
    private final L2TtlAdjuster adjuster;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AvalancheTtlCache> wrapped = new ConcurrentHashMap<>();

    public AvalancheCacheResolver(CacheManager cacheManager,
                                  CacheDefenseModeService modeService,
                                  TtlJitter jitter,
                                  L2TtlAdjuster adjuster,
                                  MeterRegistry meterRegistry) {
        this.cacheManager = Objects.requireNonNull(cacheManager, "cacheManager");
        this.modeService = Objects.requireNonNull(modeService, "modeService");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.adjuster = Objects.requireNonNull(adjuster, "adjuster");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public Collection<? extends Cache> resolveCaches(CacheOperationInvocationContext<?> context) {
        List<Cache> caches = new ArrayList<>();
        for (String name : context.getOperation().getCacheNames()) {
            Cache delegate = cacheManager.getCache(name);
            if (delegate == null) {
                throw new IllegalStateException("Cannot find cache named '" + name + "' for "
                        + context.getOperation());
            }
            caches.add(delegate instanceof NoOpCache ? delegate : wrap(name, delegate));
        }
        if (caches.isEmpty()) {
            throw new IllegalStateException(
                    "No cache could be resolved for " + context.getOperation());
        }
        return caches;
    }

    private Cache wrap(String name, Cache delegate) {
        return wrapped.compute(name, (cacheName, existing) ->
                existing != null && existing.getNativeCache() == delegate.getNativeCache()
                        ? existing
                        : new AvalancheTtlCache(delegate, modeService, jitter, adjuster, meterRegistry));
    }
}
