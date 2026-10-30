package com.javalearn.lab.seckill.jdk8.lab.cache.preheat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.javalearn.lab.seckill.jdk8.catalog.ProductResponse;
import com.javalearn.lab.seckill.jdk8.catalog.cache.TwoLevelCache;
import com.javalearn.lab.seckill.jdk8.catalog.cache.TwoLevelCacheSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The externalized Caffeine L1 tuning: {@code maximumSize}, the asynchronous
 * {@code refreshAfterWrite} reload from L2, the {@code expireAfterWrite}
 * backstop and the {@code cache_l1_hit_ratio} gauge. Redis is mocked, so no
 * container is needed; the refresh itself runs on Caffeine's executor.
 */
class TwoLevelCacheTuningTest {

    private static final ProductResponse PRODUCT = new ProductResponse(
            1L, "old-value", new BigDecimal("1.00"), "ON_SALE", "old");
    private static final ProductResponse REFRESHED = new ProductResponse(
            1L, "new-value", new BigDecimal("2.00"), "ON_SALE", "new");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;

    @Test
    void settingsRejectAnInvertedRefreshWindow() {
        assertThatThrownBy(() -> new TwoLevelCacheSettings(
                10L, Duration.ofSeconds(60), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expireAfterWrite must exceed refreshAfterWrite");
        assertThatThrownBy(() -> new TwoLevelCacheSettings(
                0L, Duration.ofSeconds(10), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumSize");
    }

    @Test
    void maximumSizeIsTheConfiguredWValue() {
        TwoLevelCache cache = newCache(new TwoLevelCacheSettings(
                2L, Duration.ofSeconds(10), Duration.ofSeconds(60)));

        cache.put(1L, PRODUCT);
        cache.put(2L, PRODUCT);
        cache.put(3L, PRODUCT);

        assertThat(nativeCache(cache).policy().eviction()).isPresent();
        assertThat(nativeCache(cache).policy().eviction().orElseThrow(IllegalStateException::new).getMaximum())
                .isEqualTo(2L);
    }

    @Test
    void aReadAfterRefreshAfterWriteReloadsTheEntryFromL2() throws Exception {
        TwoLevelCache cache = newCache(new TwoLevelCacheSettings(
                10L, Duration.ofMillis(50), Duration.ofSeconds(5)));
        when(values.get("cache:product:1"))
                .thenReturn(objectMapper.writeValueAsString(REFRESHED));

        cache.put(1L, PRODUCT);
        assertThat(nativeCache(cache).getIfPresent(1L)).isEqualTo(PRODUCT);

        Thread.sleep(120L);
        // The hit still returns the old value immediately and triggers the
        // asynchronous reload; the L1 converges to the L2 value shortly after.
        assertThat(cache.get(1L).get()).isIn(PRODUCT, REFRESHED);
        awaitValue(cache, REFRESHED, Duration.ofSeconds(2));

        assertThat(nativeCache(cache).getIfPresent(1L)).isEqualTo(REFRESHED);
        assertThat(registry.find("cache_l2_requests").tag("result", "hit").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void expireAfterWriteStillRemovesTheEntryWhenRefreshCannotHelp() throws Exception {
        TwoLevelCache cache = newCache(new TwoLevelCacheSettings(
                10L, Duration.ofMillis(20), Duration.ofMillis(150)));
        when(values.get("cache:product:1")).thenReturn(null);

        cache.put(1L, PRODUCT);
        Thread.sleep(300L);

        assertThat(nativeCache(cache).getIfPresent(1L)).isNull();
    }

    @Test
    void theL1HitRatioGaugeFollowsTheHitAndMissCounters() {
        TwoLevelCache cache = newCache(new TwoLevelCacheSettings(
                10L, Duration.ofSeconds(10), Duration.ofSeconds(60)));
        cache.put(1L, PRODUCT);
        cache.get(1L);
        cache.get(2L);

        assertThat(registry.find("cache_l1_hit_ratio").tag("cache", "product").gauge().value())
                .isEqualTo(0.5D);
    }

    private TwoLevelCache newCache(TwoLevelCacheSettings settings) {
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> mockValues = mock(ValueOperations.class);
        values = mockValues;
        when(redis.opsForValue()).thenReturn(mockValues);
        return new TwoLevelCache("product", ProductResponse.class, redis,
                objectMapper, registry, settings);
    }

    @SuppressWarnings("unchecked")
    private static Cache<Object, Object> nativeCache(TwoLevelCache cache) {
        return (Cache<Object, Object>) cache.getNativeCache();
    }

    private static void awaitValue(TwoLevelCache cache, Object expected, Duration budget)
            throws InterruptedException {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(nativeCache(cache).getIfPresent(1L))) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("L1 did not converge to " + expected
                + ", actual=" + nativeCache(cache).getIfPresent(1L));
    }
}
