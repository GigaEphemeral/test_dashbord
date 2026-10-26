package com.javalearn.lab.seckill.catalog.cache;

import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.lab.config.TestLabCacheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The write-side half of {@code ttl-jitter}: the decorator must rewrite the L2
 * TTL of a product write by ±percent while the mode is active, leave the cache
 * untouched in every other mode, and never turn a cache write into a failure.
 */
class AvalancheTtlCacheTest {

    private static final ProductResponse VALUE = new ProductResponse(
            1L, "极客机械键盘", new BigDecimal("299.00"), "ON_SALE", "87 键热插拔轴体");

    private final TestCache delegate = new TestCache("product");
    private final CacheDefenseModeService modeService =
            new CacheDefenseModeService(TestLabCacheProperties.defaults());
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /** TTL the two-level cache "just wrote"; null means there is nothing to jitter. */
    private final AtomicReference<Duration> remaining = new AtomicReference<>(Duration.ofSeconds(60));
    private final List<Duration> rewrites = new ArrayList<>();
    private final List<Object> rewrittenKeys = new ArrayList<>();
    private final L2TtlAdjuster adjuster = new L2TtlAdjuster() {
        @Override
        public Duration remainingTtl(Object key) {
            return remaining.get();
        }

        @Override
        public void rewrite(Object key, Duration ttl) {
            rewrites.add(ttl);
            rewrittenKeys.add(key);
        }
    };

    private AvalancheTtlCache cache;

    @BeforeEach
    void setUp() {
        // Random 0.0 -> the -20% edge: 60s becomes 48s, a deterministic assertion.
        cache = new AvalancheTtlCache(delegate, modeService, new TtlJitter(20D, () -> 0D),
                adjuster, registry);
    }

    @Test
    void aPutInTtlJitterModeRewritesTheJustWrittenL2Ttl() {
        modeService.update("ttl-jitter");

        cache.put(1L, VALUE);

        assertThat(delegate.get(1L).get()).isEqualTo(VALUE);
        assertThat(rewrites).containsExactly(Duration.ofSeconds(48));
        assertThat(rewrittenKeys).containsExactly(1L);
        assertThat(counter("applied")).isEqualTo(1.0);
        assertThat(counter("skipped")).isZero();
    }

    @Test
    void everyOtherModeLeavesTheTtlAlone() {
        for (String mode : List.of("off", "null-cache", "bloom", "mutex",
                "logical-expire", "l1-cache", "db-fallback")) {
            modeService.update(mode);
            cache.put(1L, VALUE);
        }

        assertThat(rewrites).isEmpty();
        assertThat(counter("applied")).isZero();
        assertThat(counter("skipped")).isZero();
        assertThat(delegate.get(1L).get()).isEqualTo(VALUE);
    }

    @Test
    void switchingTheModeAtRuntimeTakesEffectOnTheNextWrite() {
        modeService.update("off");
        cache.put(1L, VALUE);
        assertThat(rewrites).isEmpty();

        modeService.update("ttl-jitter");
        cache.put(1L, VALUE);

        assertThat(rewrites).containsExactly(Duration.ofSeconds(48));
    }

    @Test
    void aKeyWithoutATtlIsSkippedNotCountedAsApplied() {
        modeService.update("ttl-jitter");
        remaining.set(null);

        cache.put(1L, VALUE);

        assertThat(rewrites).isEmpty();
        assertThat(counter("skipped")).isEqualTo(1.0);
        assertThat(counter("applied")).isZero();
    }

    @Test
    void aFailingTtlRewriteNeverFailsTheCacheWrite() {
        modeService.update("ttl-jitter");
        L2TtlAdjuster boom = new L2TtlAdjuster() {
            @Override
            public Duration remainingTtl(Object key) {
                return Duration.ofSeconds(60);
            }

            @Override
            public void rewrite(Object key, Duration ttl) {
                throw new IllegalStateException("redis is down");
            }
        };
        cache = new AvalancheTtlCache(delegate, modeService, new TtlJitter(20D, () -> 0D),
                boom, registry);

        assertThatCode(() -> cache.put(1L, VALUE)).doesNotThrowAnyException();

        assertThat(delegate.get(1L).get()).isEqualTo(VALUE);
        assertThat(counter("error")).isEqualTo(1.0);
        assertThat(counter("applied")).isZero();
    }

    @Test
    void readsEvictsAndClearsDelegateUnchanged() {
        delegate.put(1L, VALUE);

        assertThat(cache.get(1L).get()).isEqualTo(VALUE);
        assertThat(cache.get(1L, ProductResponse.class)).isEqualTo(VALUE);
        assertThat(cache.get(2L, () -> VALUE)).isEqualTo(VALUE);
        assertThat(cache.getName()).isEqualTo("product");
        assertThat(cache.getNativeCache()).isSameAs(delegate.getNativeCache());

        cache.evict(1L);
        assertThat(delegate.get(1L)).isNull();

        cache.put(1L, VALUE);
        cache.clear();
        assertThat(delegate.get(1L)).isNull();
    }

    @Test
    void aCacheToggleDisabledNoOpCacheIsRecognisedByName() {
        assertThat(new NoOpCache("product").getName()).isEqualTo("product");
    }

    private double counter(String result) {
        return registry.get("cache_avalanche_ttl_jitter").tag("cache", "product")
                .tag("result", result).counter().count();
    }

    /** Minimal in-memory Cache, so no Spring context or Redis is needed. */
    static final class TestCache implements Cache {

        private final String name;
        private final Map<Object, Object> entries = new ConcurrentHashMap<>();

        TestCache(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return entries;
        }

        @Override
        public ValueWrapper get(Object key) {
            Object value = entries.get(key);
            return value == null ? null : () -> value;
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            Object value = entries.get(key);
            return type.isInstance(value) ? type.cast(value) : null;
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            Object value = entries.get(key);
            if (value == null) {
                try {
                    value = valueLoader.call();
                } catch (Exception exception) {
                    throw new ValueRetrievalException(key, valueLoader, exception);
                }
                entries.put(key, value);
            }
            @SuppressWarnings("unchecked")
            T typed = (T) value;
            return typed;
        }

        @Override
        public void put(Object key, Object value) {
            entries.put(key, value);
        }

        @Override
        public void evict(Object key) {
            entries.remove(key);
        }

        @Override
        public void clear() {
            entries.clear();
        }
    }
}
