package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.catalog.ProductResponse;
import io.micrometer.core.instrument.Counter;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3-01 close semantics of {@link TwoLevelCache}: off bypasses L1 for reads and
 * writes, clears the local entries, stops counting L1 hits/misses, and keeps
 * L2/Redis completely untouched. On re-enable the cache starts empty.
 *
 * <p>Redis is a small in-memory mock (no container): the point is the L1 branch,
 * not Redis itself, and the mock lets the test prove exactly which keys were
 * written or deleted.</p>
 */
class TwoLevelCacheL1SwitchTest {

    private static final ProductResponse PRODUCT = new ProductResponse(
            1L, "极客机械键盘", new BigDecimal("299.00"), "ON_SALE", "87 键热插拔轴体");
    private static final ProductResponse PRODUCT_2 = new ProductResponse(
            2L, "降噪耳机", new BigDecimal("499.00"), "ON_SALE", "主动降噪");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> l2 = new LinkedHashMap<>();

    private SimpleMeterRegistry registry;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> operations;
    private TwoLevelCache cache;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> mockOperations = mock(ValueOperations.class);
        operations = mockOperations;
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.get(anyString()))
                .thenAnswer(invocation -> l2.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            l2.put(invocation.getArgument(0, String.class),
                    invocation.getArgument(1, String.class));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));
        cache = new TwoLevelCache("product", ProductResponse.class, redis,
                objectMapper, registry);
    }

    @Test
    void disablingClearsL1BypassesBothPathsAndKeepsL2Untouched() {
        cache.put(1L, PRODUCT);
        assertThat(cache.l1Size()).isEqualTo(1L);
        assertThat(l2).containsKey("cache:product:1");
        assertThat(cache.get(1L).get()).isEqualTo(PRODUCT);
        assertThat(counter("cache_l1_requests", "result", "hit")).isEqualTo(1.0);

        long cleared = cache.setL1Enabled(false);

        assertThat(cleared).isEqualTo(1L);
        assertThat(cache.isL1Enabled()).isFalse();
        assertThat(cache.l1Size()).isZero();
        assertThat(gauge("cache_l1_enabled")).isZero();
        assertThat(l2).containsKey("cache:product:1");
        verify(redis, never()).delete(anyString());

        double hitsBefore = counter("cache_l1_requests", "result", "hit");
        double missesBefore = counter("cache_l1_requests", "result", "miss");

        // A read goes L2 -> source directly and is never copied back into L1.
        assertThat(cache.get(1L).get()).isEqualTo(PRODUCT);
        assertThat(cache.l1Size()).isZero();
        assertThat(counter("cache_l1_requests", "result", "hit")).isEqualTo(hitsBefore);
        assertThat(counter("cache_l1_requests", "result", "miss")).isEqualTo(missesBefore);
        assertThat(counter("cache_l2_requests", "result", "hit")).isEqualTo(1.0);

        // An L2 miss is still a DB fallback, not an L1 miss.
        assertThat(cache.get(2L)).isNull();
        assertThat(counter("cache_l1_requests", "result", "miss")).isEqualTo(missesBefore);
        assertThat(counter("cache_db_fallbacks", "reason", "miss")).isEqualTo(1.0);

        // Writes keep filling L2 but never fill the disabled L1.
        cache.put(2L, PRODUCT_2);
        assertThat(l2).containsKey("cache:product:2");
        assertThat(cache.l1Size()).isZero();
        assertThat(cache.get(2L).get()).isEqualTo(PRODUCT_2);
        assertThat(cache.l1Size()).isZero();
    }

    @Test
    void enablingStartsFromAnEmptyL1AndResumesNormalHits() {
        cache.put(1L, PRODUCT);
        cache.setL1Enabled(false);

        long cleared = cache.setL1Enabled(true);

        assertThat(cleared).isZero();
        assertThat(cache.isL1Enabled()).isTrue();
        assertThat(gauge("cache_l1_enabled")).isEqualTo(1.0);
        assertThat(cache.l1Size()).isZero();

        // First read after enabling comes from L2 and repopulates L1.
        assertThat(cache.get(1L).get()).isEqualTo(PRODUCT);
        assertThat(cache.l1Size()).isEqualTo(1L);
        double hits = counter("cache_l1_requests", "result", "hit");
        assertThat(cache.get(1L).get()).isEqualTo(PRODUCT);
        assertThat(counter("cache_l1_requests", "result", "hit")).isEqualTo(hits + 1);
    }

    @Test
    void sameValueIsIdempotentAndOnceDisabledNoL1CounterMoves() {
        cache.put(1L, PRODUCT);

        assertThat(cache.setL1Enabled(true)).isZero();
        assertThat(cache.l1Size()).isEqualTo(1L);
        assertThat(cache.setL1Enabled(true)).isZero();
        assertThat(cache.l1Size()).isEqualTo(1L);

        assertThat(cache.setL1Enabled(false)).isEqualTo(1L);
        assertThat(cache.setL1Enabled(false)).isZero();
        assertThat(gauge("cache_l1_enabled")).isZero();
    }

    @Test
    void clearL1ReportsTheExactSizeAndNeverDeletesL2Keys() {
        cache.put(1L, PRODUCT);
        cache.put(2L, PRODUCT_2);

        assertThat(cache.clearL1()).isEqualTo(2L);
        assertThat(cache.l1Size()).isZero();
        assertThat(l2).containsKeys("cache:product:1", "cache:product:2");
        assertThat(cache.isL1Enabled()).isTrue();
        assertThat(gauge("cache_l1_enabled")).isEqualTo(1.0);
        assertThat(cache.clearL1()).isZero();
        verify(redis, never()).delete(anyString());
    }

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double gauge(String name) {
        return registry.get(name).tag("cache", "product").gauge().value();
    }
}
