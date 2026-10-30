package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.javalearn.lab.seckill.jdk8.catalog.ProductResponse;
import com.javalearn.lab.seckill.jdk8.support.RedisTestContainer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Redis L2 semantics of {@link TwoLevelCache} on the JDK 8 shell.
 *
 * <p>The fast unit test ({@code TwoLevelCacheTest}) drives an in-memory
 * {@code FakeRedis}; this IT runs the same production class against the lab's
 * Redis 7.4.2 image so JSON round-tripping, real key/TTL layout and SCAN-based
 * clear are verified by the server, not by a stand-in.</p>
 */
class TwoLevelCacheRedisIT {

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
    void missFillsL1AndRealRedisL2WithTheDocumentedKeyAndTtl() {
        assertThat(cache.get(1L)).isNull();
        assertThat(counter("cache_l1_requests", "result", "miss")).isEqualTo(1.0);
        assertThat(counter("cache_l2_requests", "result", "miss")).isEqualTo(1.0);
        assertThat(counter("cache_db_fallbacks", "reason", "miss")).isEqualTo(1.0);

        cache.put(1L, PRODUCT);

        assertThat(redis.opsForValue().get("cache:product:1")).isNotNull();
        assertThat(redis.getExpire("cache:product:1")).isPositive();

        assertThat(cache.get(1L)).isNotNull();
        assertThat(cache.get(1L).get()).isEqualTo(PRODUCT);
        assertThat(counter("cache_l1_requests", "result", "hit")).isEqualTo(2.0);
        assertThat(counter("cache_l2_errors")).isZero();
    }

    @Test
    void l2HitFromRealRedisRepopulatesL1() {
        cache.put(1L, PRODUCT);
        nativeCache().invalidateAll();
        assertThat(nativeCache().getIfPresent(1L)).isNull();

        assertThat(cache.get(1L).get()).isEqualTo(PRODUCT);

        assertThat(counter("cache_l2_requests", "result", "hit")).isEqualTo(1.0);
        assertThat(counter("cache_db_fallbacks", "reason", "miss")).isZero();
        assertThat(nativeCache().getIfPresent(1L)).isEqualTo(PRODUCT);
    }

    @Test
    void evictDeletesTheRealRedisKey() {
        cache.put(1L, PRODUCT);
        assertThat(redis.opsForValue().get("cache:product:1")).isNotNull();

        cache.evict(1L);

        assertThat(nativeCache().getIfPresent(1L)).isNull();
        assertThat(redis.opsForValue().get("cache:product:1")).isNull();
        assertThat(cache.get(1L)).isNull();
    }

    @Test
    void clearScansRealRedisAndLeavesOtherPrefixesAlone() {
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
    void boundSettingsDriveTheRealRedisKeyLayoutAndTtl() {
        TwoLevelCacheSettings tuned = new TwoLevelCacheSettings(
                1_000L, Duration.ofSeconds(10), Duration.ofSeconds(60), false,
                Duration.ofSeconds(30), "lab-l2:", 250L);
        TwoLevelCache configured = new TwoLevelCache("product", ProductResponse.class,
                redis, new ObjectMapper(), new SimpleMeterRegistry(), tuned);

        assertThat(configured.l2Ttl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(configured.redisKeyPrefix()).isEqualTo("lab-l2:product:");

        configured.put(1L, PRODUCT);

        assertThat(redis.opsForValue().get("lab-l2:product:1")).isNotNull();
        assertThat(redis.opsForValue().get("cache:product:1")).isNull();
        Long remainingSeconds = redis.getExpire("lab-l2:product:1");
        assertThat(remainingSeconds).isNotNull().isBetween(1L, 30L);
    }

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> nativeCache() {
        return (Cache<Object, Object>) cache.getNativeCache();
    }
}
