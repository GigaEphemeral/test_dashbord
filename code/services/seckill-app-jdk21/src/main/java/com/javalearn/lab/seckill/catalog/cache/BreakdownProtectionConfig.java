package com.javalearn.lab.seckill.catalog.cache;

import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the breakdown (击穿) stage of the product read path (design 8.4).
 *
 * <p>Every number — lock prefix/wait/lease, logical TTL, stale retention, store
 * bound, refresh pool size, queue capacity, keep-alive and thread-name prefix —
 * comes from {@code app.lab.cache.breakdown.*}; nothing is hardcoded here. The
 * Redisson client is the shared {@code seckillRedissonClient} bean, so the
 * breakdown lock, the idempotency store and the bloom filter keep using one
 * connection pool and one endpoint.</p>
 */
@Configuration(proxyBeanMethods = false)
public class BreakdownProtectionConfig {

    @Bean
    public LogicalStaleStore logicalStaleStore(LabCacheProperties properties) {
        LabCacheProperties.LogicalExpire config = properties.getBreakdown().getLogicalExpire();
        return new LogicalStaleStore(config.getMaxEntries(),
                Duration.ofSeconds(config.getRetentionSeconds()),
                Duration.ofSeconds(config.getLogicalTtlSeconds()),
                System::nanoTime);
    }

    /**
     * The bounded executor behind the logical-expire refresh. Fixed size, fixed
     * queue, daemon threads and an abort-on-saturation policy: saturation is
     * counted as {@code cache_breakdown_refresh{result="rejected"}} and the
     * stale value keeps serving instead of blocking the reader.
     */
    @Bean(name = "breakdownRefreshExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor breakdownRefreshExecutor(LabCacheProperties properties) {
        return newRefreshExecutor(properties.getBreakdown().getLogicalExpire());
    }

    static ThreadPoolExecutor newRefreshExecutor(LabCacheProperties.LogicalExpire config) {
        int size = config.getRefreshPoolSize();
        ThreadFactory threads = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        config.getThreadNamePrefix() + "-" + sequence.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(size, size,
                config.getKeepAliveSeconds(), TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.getRefreshQueueCapacity()),
                threads, new ThreadPoolExecutor.AbortPolicy());
        // A quiet lab must not hold threads open forever; the queue + pool size
        // still bound the refresh concurrency.
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean
    public ProductBreakdownDefense productBreakdownDefense(
            CacheDefenseModeService cacheDefenseModeService,
            ProductPenetrationDefense productPenetrationDefense,
            @Qualifier("seckillRedissonClient") RedissonClient redissonClient,
            LogicalStaleStore logicalStaleStore,
            @Qualifier("breakdownRefreshExecutor") ThreadPoolExecutor breakdownRefreshExecutor,
            CacheManager cacheManager,
            LabCacheProperties properties,
            MeterRegistry meterRegistry) {
        return new ProductBreakdownDefense(cacheDefenseModeService, productPenetrationDefense,
                redissonClient, logicalStaleStore, breakdownRefreshExecutor,
                springCacheProbe(cacheManager), properties, System::currentTimeMillis, meterRegistry);
    }

    /**
     * Mutex double-check probe: ask the Spring two-level cache exactly the way
     * {@code @Cacheable} does (L1 then L2; a hit also promotes to L1). A probe
     * failure must never break the read path, so it degrades to "miss".
     */
    static ProductBreakdownDefense.CacheProbe springCacheProbe(CacheManager cacheManager) {
        return id -> {
            Cache cache = cacheManager.getCache(TwoLevelCacheManager.PRODUCT_CACHE);
            if (cache == null) {
                return Optional.empty();
            }
            try {
                Cache.ValueWrapper wrapper = cache.get(id);
                Object value = wrapper == null ? null : wrapper.get();
                return value instanceof ProductResponse response
                        ? Optional.of(response)
                        : Optional.empty();
            } catch (RuntimeException ex) {
                return Optional.empty();
            }
        };
    }
}
