package com.javalearn.lab.seckill.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the custom two-level cache as the application's only CacheManager, so
 * {@code @Cacheable} keeps working while reads flow Caffeine L1 -> Redis L2 ->
 * database. Defining this bean also backs off Boot's auto-configured
 * (single-level) cache managers.
 */
@Configuration
@EnableCaching
public class TwoLevelCacheConfig {

    @Bean
    public CacheManager cacheManager(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     MeterRegistry meterRegistry,
                                     LabToggleService labToggleService,
                                     TwoLevelCacheSettings twoLevelCacheSettings) {
        TwoLevelCacheManager manager = new TwoLevelCacheManager(
                redisTemplate, objectMapper, meterRegistry, labToggleService,
                twoLevelCacheSettings);
        // Eagerly create the product cache so its metrics exist before the
        // first request and a cold start is visible on the dashboard. When the
        // cache toggle is seeded off this returns a no-op cache and no meters
        // are registered until the first enabled request; that is the intended
        // "bypass both levels" behavior, not a missing metric.
        manager.getCache(TwoLevelCacheManager.PRODUCT_CACHE);
        return manager;
    }
}
