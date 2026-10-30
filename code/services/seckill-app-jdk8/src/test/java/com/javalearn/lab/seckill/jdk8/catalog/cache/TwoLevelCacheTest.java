package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.javalearn.lab.seckill.jdk8.support.FakeRedis;
import com.javalearn.lab.seckill.jdk8.catalog.ProductResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L1/L2 behaviour of {@link TwoLevelCache} against a real Redis 7.4.2.
 *
 * <p>The Caffeine L1 is invalidated directly through its native cache to prove
 * the Redis L2 path, and a mocked {@link StringRedisTemplate} proves that a
 * Redis outage degrades to a miss instead of an exception.</p>
 */
class TwoLevelCacheTest {

    private static final ProductResponse PRODUCT = new ProductResponse(
            1L, "极客机械键盘", new BigDecimal("299.00"), "ON_SALE", "87 键热插拔轴体");
    private static final ProductResponse PRODUCT_2 = new ProductResponse(
            2L, "降噪耳机", new BigDecimal("499.00"), "ON_SALE", "主动降噪");

    private SimpleMeterRegistry registry;
    private FakeRedis fakeRedis;
    private StringRedisTemplate redis;
    private TwoLevelCache cache;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        fakeRedis = new FakeRedis();
        redis = fakeRedis.template();
        cache = new TwoLevelCache("product", ProductResponse.class, redis, new ObjectMapper(), registry);
    }

    @Test
    void missFillsBothLevelsAndSecondReadHitsL1() {
        assertThat(cache.get(1L)).isNull();
        assertThat(counter("cache_l1_requests", "result", "miss")).isEqualTo(1.0);
        assertThat(counter("cache_l1_requests", "result", "hit")).isZero();
        assertThat(counter("cache_l2_requests", "result", "miss")).isEqualTo(1.0);
        assertThat(counter("cache_db_fallbacks", "reason", "miss")).isEqualTo(1.0);

        cache.put(1L, PRODUCT);

        assertThat(redis.opsForValue().get("cache:product:1")).isNotNull();
        assertThat(redis.getExpire("cache:product:1")).isPositive();

        assertThat(cache.get(1L)).isNotNull();
        assertThat(cache.get(1L).get()).isEqualTo(PRODUCT);
        assertThat(counter("cache_l1_requests", "result", "hit")).isEqualTo(2.0);
        assertThat(counter("cache_db_fallbacks", "reason", "miss")).isEqualTo(1.0);
        assertThat(counter("cache_l2_errors")).isZero();
    }

    @Test
    void l2HitRepopulatesL1AfterL1Invalidation() {
        cache.put(1L, PRODUCT);
        nativeCache().invalidateAll();
        assertThat(nativeCache().getIfPresent(1L)).isNull();

        assertThat(cache.get(1L).get()).isEqualTo(PRODUCT);

        assertThat(counter("cache_l2_requests", "result", "hit")).isEqualTo(1.0);
        assertThat(counter("cache_db_fallbacks", "reason", "miss")).isZero();
        assertThat(nativeCache().getIfPresent(1L)).isEqualTo(PRODUCT);
    }

    @Test
    void evictRemovesL1AndL2() {
        cache.put(1L, PRODUCT);
        assertThat(redis.opsForValue().get("cache:product:1")).isNotNull();

        cache.evict(1L);

        assertThat(nativeCache().getIfPresent(1L)).isNull();
        assertThat(redis.opsForValue().get("cache:product:1")).isNull();
        assertThat(cache.get(1L)).isNull();
        assertThat(counter("cache_db_fallbacks", "reason", "miss")).isEqualTo(1.0);
    }

    @Test
    void clearRemovesAllEntriesForThisCacheOnly() {
        cache.put(1L, PRODUCT);
        cache.put(2L, PRODUCT_2);
        redis.opsForValue().set("cache:other:1", "keep-me");

        cache.clear();

        assertThat(nativeCache().estimatedSize()).isZero();
        assertThat(redis.opsForValue().get("cache:product:1")).isNull();
        assertThat(redis.opsForValue().get("cache:product:2")).isNull();
        assertThat(redis.opsForValue().get("cache:other:1")).isEqualTo("keep-me");
    }

    @Test
    void redisFailureDegradesToMissAndIsCounted() {
        StringRedisTemplate failingRedis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(failingRedis.opsForValue()).thenReturn(operations);
        when(operations.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis is down"));
        doThrow(new RedisConnectionFailureException("redis is down"))
                .when(operations).set(anyString(), anyString(), any(Duration.class));

        SimpleMeterRegistry failingRegistry = new SimpleMeterRegistry();
        TwoLevelCache failingCache = new TwoLevelCache(
                "product", ProductResponse.class, failingRedis, new ObjectMapper(), failingRegistry);

        assertThat(failingCache.get(1L)).isNull();

        assertThat(counter(failingRegistry, "cache_l2_errors")).isEqualTo(1.0);
        assertThat(counter(failingRegistry, "cache_l2_requests", "result", "miss")).isEqualTo(1.0);
        assertThat(counter(failingRegistry, "cache_db_fallbacks", "reason", "miss")).isEqualTo(1.0);

        assertThatCode(() -> failingCache.put(1L, PRODUCT)).doesNotThrowAnyException();
        assertThat(counter(failingRegistry, "cache_l2_errors")).isEqualTo(2.0);
        // The L1 write still happened even though Redis rejected the value.
        assertThat(failingCache.get(1L).get()).isEqualTo(PRODUCT);
    }

    /**
     * B0-04 turned three compile-time constants of this class into configuration;
     * this proves the bound values really drive the Redis write, so a lab run can
     * change the L2 lifetime without a rebuild and the changed key TTL matches
     * what {@code GET /lab/config} reports.
     */
    @Test
    void boundSettingsDecideTheL2KeyLayoutAndWriteTtl() {
        TwoLevelCacheSettings tuned = new TwoLevelCacheSettings(
                1_000L, Duration.ofSeconds(10), Duration.ofSeconds(60), false,
                Duration.ofSeconds(30), "lab-l2:", 250L);
        TwoLevelCache configured = new TwoLevelCache("product", ProductResponse.class,
                redis, new ObjectMapper(), new SimpleMeterRegistry(), tuned);

        assertThat(configured.l2Ttl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(configured.redisKeyPrefix()).isEqualTo("lab-l2:product:");
        assertThat(configured.l2ClearScanCount()).isEqualTo(250L);

        configured.put(1L, PRODUCT);

        assertThat(redis.opsForValue().get("lab-l2:product:1")).isNotNull();
        // The historical `cache:` layout is gone for this instance: a reader that
        // still hardcodes it would find nothing.
        assertThat(redis.opsForValue().get("cache:product:1")).isNull();
        Long remainingSeconds = redis.getExpire("lab-l2:product:1");
        assertThat(remainingSeconds).isNotNull().isBetween(1L, 30L);
    }

    private double counter(String name, String... tags) {
        return counter(registry, name, tags);
    }

    private static double counter(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> nativeCache() {
        return (Cache<Object, Object>) cache.getNativeCache();
    }
}
