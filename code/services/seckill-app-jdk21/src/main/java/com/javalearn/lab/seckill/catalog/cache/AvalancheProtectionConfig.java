package com.javalearn.lab.seckill.catalog.cache;

import com.javalearn.lab.seckill.lab.config.LabCacheAvalancheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the avalanche (雪崩) stage of the product read path (design 8.4).
 *
 * <p>Every number — jitter percent, refresh interval, stale window, mirror
 * bound, refresh pool/queue/keep-alive, limiter permits and period — comes from
 * {@code app.lab.cache.avalanche.*} through
 * {@link LabCacheAvalancheProperties}; nothing is hardcoded here. The stage
 * reuses the shared {@code seckillRedissonClient}-free plumbing: the cache
 * writer goes through the same Spring cache the read path uses, and the L2 TTL
 * adjuster reads the same product key layout
 * ({@link TwoLevelCacheManager#productL2KeyPrefix()}).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LabCacheAvalancheProperties.class)
public class AvalancheProtectionConfig {

    /** Production jitter source: per-write uniform draw, no shared RNG state. */
    @Bean
    public TtlJitter ttlJitter(LabCacheAvalancheProperties properties) {
        return new TtlJitter(properties.getTtlJitter().getPercent(),
                ThreadLocalRandom.current()::nextDouble);
    }

    /** The 熔断降级 budget: at most {@code permits} DB reads per {@code period-ms}. */
    @Bean
    public AvalancheLimiter avalancheLimiter(LabCacheAvalancheProperties properties) {
        LabCacheAvalancheProperties.DbFallback config = properties.getDbFallback();
        return new AvalancheLimiter(config.getPermits(), config.getPeriodMs(),
                System::currentTimeMillis);
    }

    /**
     * The L1-first mirror of the {@code l1-cache} mode. Retention is the stale
     * window (the documented staleness cap), the logical TTL is the refresh
     * interval, and the store is bounded so a key storm cannot grow it.
     */
    @Bean(name = "avalancheL1Store")
    public LogicalStaleStore avalancheL1Store(LabCacheAvalancheProperties properties) {
        LabCacheAvalancheProperties.L1Cache config = properties.getL1Cache();
        return new LogicalStaleStore(config.getMaxEntries(),
                Duration.ofSeconds(config.getStaleWindowSeconds()),
                Duration.ofSeconds(config.getRefreshIntervalSeconds()),
                System::nanoTime);
    }

    /**
     * Bounded executor behind the async L1 refresh: fixed size, fixed queue,
     * daemon threads, abort on saturation — saturation is counted as
     * {@code cache_avalanche_l1_refresh{result="rejected"}} and the stale value
     * keeps serving instead of blocking the reader.
     */
    @Bean(name = "avalancheL1RefreshExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor avalancheL1RefreshExecutor(LabCacheAvalancheProperties properties) {
        return newL1RefreshExecutor(properties.getL1Cache());
    }

    static ThreadPoolExecutor newL1RefreshExecutor(LabCacheAvalancheProperties.L1Cache config) {
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
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean
    public ProductAvalancheDefense productAvalancheDefense(
            CacheDefenseModeService cacheDefenseModeService,
            ProductBreakdownDefense productBreakdownDefense,
            @Qualifier("avalancheL1Store") LogicalStaleStore avalancheL1Store,
            @Qualifier("avalancheL1RefreshExecutor") ThreadPoolExecutor avalancheL1RefreshExecutor,
            AvalancheLimiter avalancheLimiter,
            CacheManager cacheManager,
            MeterRegistry meterRegistry) {
        return new ProductAvalancheDefense(cacheDefenseModeService, productBreakdownDefense,
                avalancheL1Store, avalancheL1RefreshExecutor,
                BreakdownProtectionConfig.springCacheWriter(cacheManager),
                avalancheLimiter, System::currentTimeMillis, meterRegistry);
    }

    /** Reads/writes the TTL of exactly the keys {@link TwoLevelCache} writes. */
    @Bean
    public L2TtlAdjuster l2TtlAdjuster(StringRedisTemplate redisTemplate) {
        return new RedisL2TtlAdjuster(redisTemplate,
                TwoLevelCacheManager.productL2KeyPrefix());
    }

    /**
     * The resolver named by {@code @Cacheable(cacheResolver = ...)} on the
     * product read: it wraps the product cache in the jittering decorator and
     * leaves every other cache untouched.
     */
    @Bean(name = "avalancheCacheResolver")
    public CacheResolver avalancheCacheResolver(CacheManager cacheManager,
                                                CacheDefenseModeService modeService,
                                                TtlJitter ttlJitter,
                                                L2TtlAdjuster l2TtlAdjuster,
                                                MeterRegistry meterRegistry) {
        return new AvalancheCacheResolver(cacheManager, modeService, ttlJitter,
                l2TtlAdjuster, meterRegistry);
    }
}
