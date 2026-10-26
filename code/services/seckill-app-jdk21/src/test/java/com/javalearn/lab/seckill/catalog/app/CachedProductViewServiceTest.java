package com.javalearn.lab.seckill.catalog.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.catalog.cache.AvalancheLimiter;
import com.javalearn.lab.seckill.catalog.cache.LogicalStaleStore;
import com.javalearn.lab.seckill.catalog.cache.NullValueMarkerStore;
import com.javalearn.lab.seckill.catalog.cache.ProductAvalancheDefense;
import com.javalearn.lab.seckill.catalog.cache.ProductBloomFilterService;
import com.javalearn.lab.seckill.catalog.cache.ProductBreakdownDefense;
import com.javalearn.lab.seckill.catalog.cache.ProductPenetrationDefense;
import com.javalearn.lab.seckill.catalog.domain.Product;
import com.javalearn.lab.seckill.lab.config.LabCacheAvalancheProperties;
import com.javalearn.lab.seckill.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.lab.config.TestLabCacheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read-path contract the lab demonstrates: whatever the penetration mode, an
 * absent product stays a {@code 40401} and a present product stays the same
 * response body. Only whether the database had to be asked changes.
 *
 * <p>Spring's cache proxy is not involved here (a unit test has no cache
 * interceptor), so every case starts from an L1+L2 miss, which is exactly when
 * {@link ProductPenetrationDefense} runs.</p>
 */
class CachedProductViewServiceTest {

    private static final Product PRODUCT = new Product(
            1L, "极客机械键盘", new BigDecimal("299.00"), "ON_SALE", "87 键热插拔轴体");

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBloomFilter<Long> filter = mock(RBloomFilter.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProductReadService readService = mock(ProductReadService.class);
    private final CacheDefenseModeService modeService;
    private final CachedProductViewService service;

    CachedProductViewServiceTest() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();
        this.modeService = new CacheDefenseModeService(properties);
        NullValueMarkerStore markers = new NullValueMarkerStore(redis, "null:product:",
                Duration.ofSeconds(10), Duration.ofSeconds(5), () -> 0L, registry);
        ProductBloomFilterService bloom = new ProductBloomFilterService(redisson,
                "bloom:product-ids", 10_000L, 0.01D, registry);
        ProductPenetrationDefense penetration =
                new ProductPenetrationDefense(modeService, markers, bloom, registry);
        // The breakdown stage is active but seeded in off mode, so every case
        // here delegates exactly like M4-1 did; mutex/logical-expire decision
        // tables live in ProductBreakdownDefenseTest.
        ProductBreakdownDefense breakdown = new ProductBreakdownDefense(modeService, penetration,
                redisson,
                new LogicalStaleStore(properties.getBreakdown().getLogicalExpire().getMaxEntries(),
                        Duration.ofSeconds(120), Duration.ofSeconds(30), System::nanoTime),
                Runnable::run, id -> Optional.empty(), (id, value) -> Optional.empty(),
                properties, System::currentTimeMillis, registry);
        // The avalanche stage dispatches first and, in off mode, delegates to the
        // breakdown stage unchanged — so these cases still prove the M4-1/M4-2
        // contracts of the complete read path.
        LabCacheAvalancheProperties avalanche = avalancheProperties();
        ProductAvalancheDefense avalancheDefense = new ProductAvalancheDefense(modeService,
                breakdown,
                new LogicalStaleStore(avalanche.getL1Cache().getMaxEntries(),
                        Duration.ofSeconds(avalanche.getL1Cache().getStaleWindowSeconds()),
                        Duration.ofSeconds(avalanche.getL1Cache().getRefreshIntervalSeconds()),
                        System::nanoTime),
                Runnable::run, (id, value) -> Optional.empty(),
                new AvalancheLimiter(avalanche.getDbFallback().getPermits(),
                        avalanche.getDbFallback().getPeriodMs(), System::currentTimeMillis),
                avalanche, System::currentTimeMillis, registry);
        this.service = new CachedProductViewService(readService, avalancheDefense);
    }

    private static LabCacheAvalancheProperties avalancheProperties() {
        LabCacheAvalancheProperties config = new LabCacheAvalancheProperties();
        config.getTtlJitter().setPercent(20D);
        config.getL1Cache().setRefreshIntervalSeconds(10L);
        config.getL1Cache().setStaleWindowSeconds(60L);
        config.getL1Cache().setMaxEntries(10_000L);
        config.getL1Cache().setRefreshPoolSize(2);
        config.getL1Cache().setRefreshQueueCapacity(100);
        config.getL1Cache().setKeepAliveSeconds(60L);
        config.getL1Cache().setThreadNamePrefix("avalanche-l1-refresh");
        config.getDbFallback().setPermits(50L);
        config.getDbFallback().setPeriodMs(1_000L);
        return config;
    }

    @BeforeEach
    void stubBackends() {
        when(redis.opsForValue()).thenReturn(values);
        when(redisson.<Long>getBloomFilter("bloom:product-ids")).thenReturn(filter);
    }

    @Test
    void anAbsentProductIsAFourOhFourInOffMode() {
        stubMissing(404L);

        assertNotFound(404L);
        verify(readService).get(404L);
    }

    @Test
    void anExistingProductIsUnaffectedByTheDefense() {
        when(readService.get(1L)).thenReturn(PRODUCT);

        assertThat(service.get(1L).id()).isEqualTo(1L);
        assertThat(service.get(1L).name()).isEqualTo("极客机械键盘");
        assertNoMarkerWritten();
    }

    @Test
    void aNullCacheMarkerShortensTheSecondMissToZeroDatabaseReads() {
        modeService.update("null-cache");
        when(values.get("null:product:404")).thenReturn(null, "NULL");
        stubMissing(404L);

        assertNotFound(404L);
        assertNotFound(404L);

        verify(readService).get(404L);
        verify(values).set("null:product:404", "NULL", Duration.ofSeconds(10));
        assertThat(penetrationFallbacks()).isEqualTo(1.0);
    }

    @Test
    void aBloomFalsePositiveFallsBackToTheDatabaseAndStillReturnsFourOhFour() {
        modeService.update("bloom");
        // The filter claims this id may exist although it does not: that is the
        // false positive, and it must cost a database read, never a 500.
        when(filter.contains(999_999L)).thenReturn(true);
        stubMissing(999_999L);

        assertNotFound(999_999L);
        verify(readService).get(999_999L);
        assertThat(penetrationFallbacks()).isEqualTo(1.0);
    }

    @Test
    void aProvenBloomNegativeNeverReachesTheDatabase() {
        modeService.update("bloom");
        when(filter.contains(999_999L)).thenReturn(false);

        assertNotFound(999_999L);
        verify(readService, never()).get(anyLong());
        assertThat(penetrationFallbacks()).isZero();
    }

    @Test
    void otherBusinessErrorsAreNeverMistakenForAbsentData() {
        modeService.update("null-cache");
        when(values.get("null:product:1")).thenReturn(null);
        when(readService.get(1L)).thenThrow(new BizException(
                CommonErrorCode.RATE_LIMITED, "Too many requests"));

        assertThatThrownBy(() -> service.get(1L))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.RATE_LIMITED);

        assertNoMarkerWritten();
    }

    private void stubMissing(long id) {
        when(readService.get(id)).thenThrow(new BizException(
                CommonErrorCode.NOT_FOUND, "Product not found: " + id));
    }

    private void assertNotFound(long id) {
        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(BizException.class)
                .hasMessage("Product not found: " + id)
                .extracting(error -> ((BizException) error).getErrorCode().code())
                .isEqualTo(CommonErrorCode.NOT_FOUND.code());
    }

    private void assertNoMarkerWritten() {
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    private double penetrationFallbacks() {
        return registry.get("cache_db_fallbacks").tag("cache", "product").tag("reason", "penetration")
                .counter().count();
    }
}
