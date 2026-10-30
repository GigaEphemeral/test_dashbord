package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.lab.config.LabToggleProperties;
import com.javalearn.lab.seckill.jdk8.lab.toggle.LabToggleService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The toggle-aware cache manager: off returns a no-op cache that never touches
 * Redis, on returns the real two-level cache again.
 */
class ToggleAwareCacheManagerTest {

    @Test
    void disabledToggleReturnsANoOpCacheWithoutRedisAccess() {
        Chip chip = new Chip();
        LabToggleService toggles = toggles(false);
        TwoLevelCacheManager manager = chip.manager(toggles);

        Cache cache = manager.getCache(TwoLevelCacheManager.PRODUCT_CACHE);

        assertThat(cache).isInstanceOf(NoOpCache.class);
        assertThat(cache.getName()).isEqualTo(TwoLevelCacheManager.PRODUCT_CACHE);
        assertThat(cache.get(1L)).isNull();
        assertThat(cache.get(1L, String.class)).isNull();
        assertThat(cache.get(1L, () -> "loaded")).isEqualTo("loaded");
        cache.put(1L, "ignored");
        cache.evict(1L);
        cache.clear();

        verify(chip.operations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void enabledToggleReturnsTheTwoLevelCacheAndResumesWrites() {
        Chip chip = new Chip();
        LabToggleService toggles = toggles(true);
        TwoLevelCacheManager manager = chip.manager(toggles);

        Cache cache = manager.getCache(TwoLevelCacheManager.PRODUCT_CACHE);
        assertThat(cache).isInstanceOf(TwoLevelCache.class);
        assertThat(manager.getCacheNames())
                .containsExactly(TwoLevelCacheManager.PRODUCT_CACHE);

        cache.put(1L, "value");
        verify(chip.operations).set("cache:" + TwoLevelCacheManager.PRODUCT_CACHE + ":1",
                "\"value\"", Duration.ofMinutes(5));
    }

    @Test
    void togglingCacheOffAndOnSwitchesTheReturnedTypeWithoutRestart() {
        Chip chip = new Chip();
        LabToggleService toggles = toggles(true);
        TwoLevelCacheManager manager = chip.manager(toggles);

        Cache twoLevel = manager.getCache(TwoLevelCacheManager.PRODUCT_CACHE);
        assertThat(twoLevel).isInstanceOf(TwoLevelCache.class);

        toggles.update("cache", false);
        assertThat(manager.getCache(TwoLevelCacheManager.PRODUCT_CACHE))
                .isInstanceOf(NoOpCache.class);

        toggles.update("cache", true);
        assertThat(manager.getCache(TwoLevelCacheManager.PRODUCT_CACHE))
                .isSameAs(twoLevel);
    }

    private static LabToggleService toggles(boolean cache) {
        LabToggleProperties properties = new LabToggleProperties();
        properties.setLock(true);
        properties.setIdempotency(true);
        properties.setMq(true);
        properties.setRatelimit(true);
        properties.setCircuitbreaker(true);
        properties.setCache(cache);
        return new LabToggleService(properties);
    }

    private static final class Chip {

        private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        private final ValueOperations<String, String> operations = mock(ValueOperations.class);
        private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        private Chip() {
            when(redisTemplate.opsForValue()).thenReturn(operations);
        }

        private TwoLevelCacheManager manager(LabToggleService toggles) {
            return new TwoLevelCacheManager(
                    redisTemplate, new ObjectMapper(), meterRegistry, toggles);
        }
    }
}
