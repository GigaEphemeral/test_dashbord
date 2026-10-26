package com.javalearn.lab.seckill.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cache manager for the two-level cache. {@code product} is created eagerly so
 * its counters are registered at startup; any future cache name is created on
 * first use with the same Caffeine + Redis layering.
 */
public final class TwoLevelCacheManager implements CacheManager {

    public static final String PRODUCT_CACHE = "product";

    private final ConcurrentMap<String, TwoLevelCache> caches = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public TwoLevelCacheManager(StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public TwoLevelCache getCache(String name) {
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
