package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.catalog.ProductResponse;
import com.javalearn.lab.seckill.jdk8.lab.config.LabToggleProperties;
import com.javalearn.lab.seckill.jdk8.lab.toggle.LabToggleService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B3-01 fan-out: one per-JVM switch must reach every managed cache (the product
 * cache and any future name), be idempotent, and make a cache created while the
 * switch is off start disabled.
 */
class TwoLevelCacheManagerL1SwitchTest {

    private static final ProductResponse PRODUCT = new ProductResponse(
            1L, "键盘", new BigDecimal("1.00"), "ON_SALE", "a");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> l2 = new LinkedHashMap<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private TwoLevelCacheManager manager;

    @BeforeEach
    void setUp() {
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
        manager = new TwoLevelCacheManager(redis, objectMapper, registry, toggles(true));
    }

    @Test
    void switchFansOutToEveryManagedCacheAndReportsTheirSizes() {
        TwoLevelCache product = (TwoLevelCache) manager.getCache(
                TwoLevelCacheManager.PRODUCT_CACHE);
        TwoLevelCache other = (TwoLevelCache) manager.getCache("other");
        product.put(1L, PRODUCT);
        other.put(2L, "b");

        assertThat(manager.isL1Enabled()).isTrue();
        assertThat(manager.cacheStatuses())
                .extracting(TwoLevelCacheManager.CacheStatus::name)
                .containsExactly("other", "product");
        assertThat(manager.cacheStatuses())
                .extracting(TwoLevelCacheManager.CacheStatus::size)
                .containsExactly(1L, 1L);

        long cleared = manager.setL1Enabled(false);

        assertThat(cleared).isEqualTo(2L);
        assertThat(manager.isL1Enabled()).isFalse();
        assertThat(product.isL1Enabled()).isFalse();
        assertThat(other.isL1Enabled()).isFalse();
        assertThat(manager.cacheStatuses())
                .extracting(TwoLevelCacheManager.CacheStatus::size)
                .containsExactly(0L, 0L);
        // L2 still has both values.
        assertThat(l2).containsKeys("cache:product:1", "cache:other:2");

        assertThat(manager.setL1Enabled(false)).isZero();

        // Reads still reach L2 while off and do not repopulate L1.
        assertThat(product.get(1L).get()).isEqualTo(PRODUCT);
        assertThat(product.l1Size()).isZero();
        assertThat(other.l1Size()).isZero();
    }

    @Test
    void cacheCreatedWhileOffStartsDisabledAndTurnsOnWithTheSwitch() {
        manager.setL1Enabled(false);

        TwoLevelCache late = (TwoLevelCache) manager.getCache("late");
        assertThat(late.isL1Enabled()).isFalse();

        late.put(7L, "v");
        assertThat(late.l1Size()).isZero();
        assertThat(l2).containsKey("cache:late:7");
        assertThat(late.get(7L).get()).isEqualTo("v");
        assertThat(late.l1Size()).isZero();

        assertThat(manager.setL1Enabled(true)).isZero();
        assertThat(late.isL1Enabled()).isTrue();
        assertThat(late.get(7L).get()).isEqualTo("v");
        assertThat(late.l1Size()).isEqualTo(1L);
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
}
