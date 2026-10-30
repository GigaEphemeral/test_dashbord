package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.javalearn.lab.seckill.jdk8.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.jdk8.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Wires the penetration stage of the product read path.
 *
 * <p>All sizes, TTLs, prefixes and the startup mode come from
 * {@code app.lab.cache.*}; the Redisson client is the existing
 * {@code seckillRedissonClient} bean so the lock, the idempotency store and the
 * bloom filter share one connection pool and one Redis endpoint.</p>
 */
@Configuration(proxyBeanMethods = false)
public class PenetrationProtectionConfig {

    @Bean
    public NullValueMarkerStore nullValueMarkerStore(StringRedisTemplate redisTemplate,
                                                     LabCacheProperties properties,
                                                     MeterRegistry meterRegistry) {
        LabCacheProperties.NullCache config = properties.getPenetration().getNullCache();
        Duration jitter = Duration.ofSeconds(config.getJitterSeconds());
        return new NullValueMarkerStore(
                redisTemplate,
                config.getKeyPrefix(),
                Duration.ofSeconds(config.getTtlSeconds()),
                jitter,
                NullValueMarkerStore.randomJitterSeconds(jitter),
                meterRegistry);
    }

    @Bean
    public ProductBloomFilterService productBloomFilterService(
            @Qualifier("seckillRedissonClient") RedissonClient redissonClient,
            LabCacheProperties properties,
            MeterRegistry meterRegistry) {
        LabCacheProperties.Bloom config = properties.getPenetration().getBloom();
        return new ProductBloomFilterService(
                redissonClient,
                config.getKey(),
                config.getExpectedInsertions(),
                config.getFalseProbability(),
                meterRegistry);
    }

    @Bean
    public ProductPenetrationDefense productPenetrationDefense(
            CacheDefenseModeService cacheDefenseModeService,
            NullValueMarkerStore nullValueMarkerStore,
            ProductBloomFilterService productBloomFilterService,
            MeterRegistry meterRegistry) {
        return new ProductPenetrationDefense(
                cacheDefenseModeService, nullValueMarkerStore, productBloomFilterService, meterRegistry);
    }
}
