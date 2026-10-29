package com.javalearn.lab.seckill.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B4-01 additions to the L1 settings contract: idle expiry is optional (0/off,
 * negative is refused) and a JVM started with {@code LAB_CACHE_L1_ENABLED=false}
 * bypasses Caffeine from the very first request instead of filling it once.
 */
class TwoLevelCacheSettingsTest {

    private static final ProductResponse PRODUCT = new ProductResponse(
            1L, "键盘", new BigDecimal("1.00"), "ON_SALE", "a");

    @Test
    void negativeAccessExpiryIsRejectedAtStartup() {
        assertThatThrownBy(() -> settings(Duration.ofSeconds(-1), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expireAfterAccess");
    }

    @Test
    void zeroAccessExpiryAndEnabledL1AreTheHistoricalDefaults() {
        TwoLevelCacheSettings defaults = TwoLevelCacheSettings.defaults();

        assertThat(defaults.expireAfterAccess()).isZero();
        assertThat(defaults.l1Enabled()).isTrue();
    }

    @Test
    void startupDisabledL1BypassesCaffeineOnTheFirstRequest() {
        Map<String, String> l2 = new LinkedHashMap<>();
        l2.put("cache:product:1", json(PRODUCT));
        StringRedisTemplate redis = mockRedis(l2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TwoLevelCache cache = new TwoLevelCache("product", ProductResponse.class, redis,
                new ObjectMapper(), registry, settings(Duration.ZERO, false));

        assertThat(cache.isL1Enabled()).isFalse();
        assertThat(cache.get(1L).get()).isEqualTo(PRODUCT);

        // The very first read went L2 -> caller; Caffeine stayed empty and the
        // L1 application counters did not move, exactly like a runtime disable.
        assertThat(cache.l1Size()).isZero();
        assertThat(counter(registry, "cache_l1_requests", "result", "hit")).isZero();
        assertThat(counter(registry, "cache_l1_requests", "result", "miss")).isZero();
        assertThat(counter(registry, "cache_l2_requests", "result", "hit")).isEqualTo(1.0d);
        assertThat(registry.get("cache_l1_enabled").tag("cache", "product").gauge().value())
                .isZero();

        // Writes keep filling L2 only, so a later enable starts from an empty L1.
        cache.put(2L, PRODUCT);
        assertThat(l2).containsKey("cache:product:2");
        assertThat(cache.l1Size()).isZero();
    }

    private static TwoLevelCacheSettings settings(Duration expireAfterAccess, boolean l1Enabled) {
        return new TwoLevelCacheSettings(
                10L, Duration.ofSeconds(10), Duration.ofSeconds(60), true,
                Duration.ofMinutes(5), "cache:", 500L,
                expireAfterAccess, l1Enabled);
    }

    private static StringRedisTemplate mockRedis(Map<String, String> l2) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.get(anyString()))
                .thenAnswer(invocation -> l2.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            l2.put(invocation.getArgument(0, String.class),
                    invocation.getArgument(1, String.class));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));
        return redis;
    }

    private static String json(ProductResponse product) {
        try {
            return new ObjectMapper().writeValueAsString(product);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static double counter(SimpleMeterRegistry registry, String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
