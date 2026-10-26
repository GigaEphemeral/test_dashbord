package com.javalearn.lab.seckill.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.javalearn.lab.seckill.RedisTestContainer;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
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
    private StringRedisTemplate redis;
    private TwoLevelCache cache;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        redis = RedisTestContainer.newStringRedisTemplate();
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
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
