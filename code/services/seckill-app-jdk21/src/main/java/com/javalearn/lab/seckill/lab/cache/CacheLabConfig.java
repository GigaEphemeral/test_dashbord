package com.javalearn.lab.seckill.lab.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.catalog.cache.NullValueMarkerStore;
import com.javalearn.lab.seckill.catalog.cache.ProductAvalancheDefense;
import com.javalearn.lab.seckill.catalog.cache.ProductBloomFilterService;
import com.javalearn.lab.seckill.catalog.cache.ProductBreakdownDefense;
import com.javalearn.lab.seckill.lab.config.LabCacheAvalancheProperties;
import com.javalearn.lab.seckill.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the experiment dataset generator/state reader. It writes catalog rows
 * through {@link JdbcTemplate} and L2 keys through the same
 * {@link StringRedisTemplate} and {@link ObjectMapper} the two-level cache uses,
 * so the seeded entries stay readable by the normal product read path.
 */
@Configuration(proxyBeanMethods = false)
public class CacheLabConfig {

    @Bean
    public CacheExperimentDataService cacheExperimentDataService(
            LabCacheProperties properties,
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ProductBloomFilterService productBloomFilterService,
            NullValueMarkerStore nullValueMarkerStore,
            CacheDefenseModeService cacheDefenseModeService,
            ProductBreakdownDefense productBreakdownDefense,
            ProductAvalancheDefense productAvalancheDefense,
            LabCacheAvalancheProperties avalancheProperties,
            LabToggleService labToggleService,
            CacheManager cacheManager) {
        return new CacheExperimentDataService(properties, jdbcTemplate, redisTemplate, objectMapper,
                productBloomFilterService, nullValueMarkerStore, cacheDefenseModeService,
                productBreakdownDefense, productAvalancheDefense, avalancheProperties,
                labToggleService, cacheManager);
    }
}
