package com.javalearn.lab.seckill.catalog.cache;

import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.lab.config.TestLabCacheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheResolver;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The resolver is the seam that applies {@code ttl-jitter} to exactly the
 * {@code @Cacheable} product read: it must wrap the product cache, reuse the
 * wrapper per cache instance, pass a toggle-off {@link NoOpCache} through
 * untouched (no extra Redis calls), and fail loudly on an unknown cache name.
 */
class AvalancheCacheResolverTest {

    private final CacheDefenseModeService modeService =
            new CacheDefenseModeService(TestLabCacheProperties.defaults());
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final L2TtlAdjuster adjuster = new L2TtlAdjuster() {
        @Override
        public Duration remainingTtl(Object key) {
            return Duration.ofSeconds(60);
        }

        @Override
        public void rewrite(Object key, Duration ttl) {
        }
    };
    private final TtlJitter jitter = new TtlJitter(20D, () -> 0.5D);

    @Test
    void wrapsTheProductCacheInTheJitteringDecoratorAndReusesIt() {
        Cache product = new AvalancheTtlCacheTest.TestCache("product");
        CacheResolver resolver = resolver(cacheManager(product));

        Collection<? extends Cache> first = resolver.resolveCaches(context("product"));
        Collection<? extends Cache> second = resolver.resolveCaches(context("product"));

        assertThat(first).hasSize(1);
        Cache resolved = first.iterator().next();
        assertThat(resolved).isInstanceOf(AvalancheTtlCache.class);
        assertThat(resolved.getName()).isEqualTo("product");
        // One wrapper per raw cache: no per-request allocation on the read path.
        assertThat(second.iterator().next()).isSameAs(resolved);
    }

    @Test
    void aToggleOffNoOpCacheIsReturnedUnwrapped() {
        NoOpCache noOp = new NoOpCache("product");
        CacheResolver resolver = resolver(cacheManager(noOp));

        Collection<? extends Cache> resolved = resolver.resolveCaches(context("product"));

        assertThat(resolved.iterator().next()).isSameAs(noOp);
    }

    @Test
    void anUnknownCacheNameIsNotSilentlyIgnored() {
        CacheResolver resolver = resolver(cacheManager(null));

        assertThatThrownBy(() -> resolver.resolveCaches(context("product")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("product");
    }

    @Test
    void anEmptyCacheNameSetIsRejected() {
        CacheResolver resolver = resolver(cacheManager(null));

        assertThatThrownBy(() -> resolver.resolveCaches(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No cache could be resolved");
    }

    @Test
    void theDecoratedWriteReallyRewritesTheTtlWhileTheModeIsActive() {
        Cache product = new AvalancheTtlCacheTest.TestCache("product");
        List<Duration> rewrites = new ArrayList<>();
        L2TtlAdjuster recording = new L2TtlAdjuster() {
            @Override
            public Duration remainingTtl(Object key) {
                return Duration.ofSeconds(60);
            }

            @Override
            public void rewrite(Object key, Duration ttl) {
                rewrites.add(ttl);
            }
        };
        CacheResolver resolver = new AvalancheCacheResolver(cacheManager(product), modeService,
                jitter, recording, registry);
        modeService.update("ttl-jitter");

        Cache resolved = resolver.resolveCaches(context("product")).iterator().next();
        resolved.put(1L, new ProductResponse(1L, "键盘", new BigDecimal("1.00"),
                "ON_SALE", "描述"));

        assertThat(rewrites).containsExactly(Duration.ofSeconds(60));
    }

    private CacheResolver resolver(CacheManager manager) {
        return new AvalancheCacheResolver(manager, modeService, jitter, adjuster, registry);
    }

    private static CacheManager cacheManager(Cache cache) {
        CacheManager manager = mock(CacheManager.class);
        when(manager.getCache("product")).thenReturn(cache);
        return manager;
    }

    private static CacheOperationInvocationContext<CacheOperation> context(String... cacheNames) {
        @SuppressWarnings("unchecked")
        CacheOperationInvocationContext<CacheOperation> context = mock(CacheOperationInvocationContext.class);
        CacheOperation operation = mock(CacheOperation.class);
        when(context.getOperation()).thenReturn(operation);
        when(operation.getCacheNames()).thenReturn(Set.of(cacheNames));
        return context;
    }
}
