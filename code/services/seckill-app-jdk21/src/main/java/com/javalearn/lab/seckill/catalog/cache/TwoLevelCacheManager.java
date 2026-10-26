package com.javalearn.lab.seckill.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.lab.toggle.LabToggle;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cache manager for the two-level cache. {@code product} is created eagerly so
 * its counters are registered at startup; any future cache name is created on
 * first use with the same Caffeine + Redis layering.
 *
 * <p>The {@code cache} lab toggle is evaluated per {@link #getCache(String)}
 * call: while off, a {@link NoOpCache} is returned so {@code @Cacheable}
 * neither reads L1/L2 nor writes them; turning it back on returns the original
 * two-level cache (whose existing entries remain valid) without a restart.</p>
 */
public final class TwoLevelCacheManager implements CacheManager {

    public static final String PRODUCT_CACHE = "product";

    private final ConcurrentMap<String, TwoLevelCache> caches = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final LabToggleService toggles;

    public TwoLevelCacheManager(StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry,
                                LabToggleService toggles) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.toggles = Objects.requireNonNull(toggles, "toggles");
    }

    @Override
    public Cache getCache(String name) {
        if (!toggles.isEnabled(LabToggle.CACHE)) {
            return new NoOpCache(name);
        }
        return caches.computeIfAbsent(name,
                cacheName -> new TwoLevelCache(cacheName, valueType(cacheName),
                        redisTemplate, objectMapper, meterRegistry));
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableSet(caches.keySet());
    }

    private static Class<?> valueType(String cacheName) {
        if (PRODUCT_CACHE.equals(cacheName)) {
            return ProductResponse.class;
        }
        return Object.class;
    }
}
