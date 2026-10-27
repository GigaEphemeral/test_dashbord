package com.javalearn.lab.seckill.lab.cache.preheat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.catalog.cache.TwoLevelCacheSettings;
import com.javalearn.lab.seckill.lab.config.LabCacheL2Properties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wires the M4-3 Caffeine preheat lab. Kept as its own {@code @Configuration}
 * (like {@code ThreadLabConfig}) so the shared lab config and
 * {@code TwoLevelCacheConfig}'s bean signatures stay untouched apart from the
 * new settings parameter.
 *
 * <p>The scheduler is a daemon pool closed by the context; the lifecycle only
 * schedules tasks on it after {@code ApplicationReadyEvent}. The MVC
 * interceptor feeds the tracker from {@code GET /api/products/*}; the preheat
 * service warms L1 from L2 and never writes L2.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LabCachePreheatProperties.class, LabCacheL2Properties.class})
public class CachePreheatConfig {

    @Bean
    public TwoLevelCacheSettings twoLevelCacheSettings(LabCachePreheatProperties properties,
                                                       LabCacheL2Properties l2Properties) {
        LabCachePreheatProperties.L1 l1 = properties.getL1();
        return new TwoLevelCacheSettings(
                l1.getMaximumSize(),
                Duration.ofSeconds(l1.getRefreshAfterWriteSeconds()),
                Duration.ofSeconds(l1.getExpireAfterWriteSeconds()),
                l1.isMicrometerStatsEnabled(),
                Duration.ofSeconds(l2Properties.getTtlSeconds()),
                l2Properties.getKeyPrefix(),
                l2Properties.getClearScanCount());
    }

    @Bean
    public HotKeyTracker hotKeyTracker(LabCachePreheatProperties properties,
                                       StringRedisTemplate redisTemplate,
                                       MeterRegistry meterRegistry) {
        return new HotKeyTracker(properties, redisTemplate, meterRegistry, Clock.systemUTC());
    }

    @Bean
    public CachePreheatService cachePreheatService(LabCachePreheatProperties properties,
                                                   StringRedisTemplate redisTemplate,
                                                   ObjectMapper objectMapper,
                                                   CacheManager cacheManager,
                                                   MeterRegistry meterRegistry,
                                                   HotKeyTracker hotKeyTracker,
                                                   TwoLevelCacheSettings twoLevelCacheSettings) {
        return new CachePreheatService(properties, redisTemplate, objectMapper, cacheManager,
                meterRegistry, hotKeyTracker, Clock.systemUTC(), twoLevelCacheSettings);
    }

    @Bean(name = "cachePreheatScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService cachePreheatScheduler(LabCachePreheatProperties properties) {
        LabCachePreheatProperties.Load load = properties.getLoad();
        AtomicLong threadIndex = new AtomicLong();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    load.getThreadNamePrefix() + "-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newScheduledThreadPool(
                Math.toIntExact(load.getSchedulerPoolSize()), factory);
    }

    @Bean
    public CachePreheatLifecycle cachePreheatLifecycle(
            LabCachePreheatProperties properties,
            CachePreheatService cachePreheatService,
            HotKeyTracker hotKeyTracker,
            @Qualifier("cachePreheatScheduler") ScheduledExecutorService cachePreheatScheduler) {
        return new CachePreheatLifecycle(
                properties, cachePreheatService, hotKeyTracker, cachePreheatScheduler);
    }

    @Bean
    public WebMvcConfigurer cacheHotKeyWebMvcConfigurer(HotKeyTracker hotKeyTracker) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HotKeyTrackingInterceptor(hotKeyTracker))
                        .addPathPatterns("/api/products/*")
                        .order(Ordered.LOWEST_PRECEDENCE);
            }
        };
    }
}
