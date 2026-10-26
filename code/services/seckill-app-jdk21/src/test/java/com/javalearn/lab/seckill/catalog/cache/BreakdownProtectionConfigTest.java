package com.javalearn.lab.seckill.catalog.cache;

import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.lab.config.TestLabCacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The wiring contract of the breakdown stage: the refresh pool is built ONLY
 * from {@code app.lab.cache.breakdown.*} numbers, saturation rejects (never
 * blocks), and the mutex double-check probe asks the Spring cache without ever
 * letting a probe failure escape into the read path.
 */
class BreakdownProtectionConfigTest {

    private static final ProductResponse VALUE = new ProductResponse(
            1L, "极客机械键盘", new BigDecimal("299.00"), "ON_SALE", "87 键热插拔轴体");

    @Test
    void refreshExecutorUsesEveryExternalizedNumber() throws Exception {
        LabCacheProperties.LogicalExpire config = TestLabCacheProperties.defaults()
                .getBreakdown().getLogicalExpire();
        ThreadPoolExecutor executor = BreakdownProtectionConfig.newRefreshExecutor(config);
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(config.getRefreshPoolSize());
            assertThat(executor.getMaximumPoolSize()).isEqualTo(config.getRefreshPoolSize());
            assertThat(executor.getQueue()).isInstanceOf(ArrayBlockingQueue.class);
            assertThat(executor.getQueue().remainingCapacity())
                    .isEqualTo(config.getRefreshQueueCapacity());
            assertThat(executor.getKeepAliveTime(TimeUnit.SECONDS))
                    .isEqualTo(config.getKeepAliveSeconds());

            AtomicReference<Thread> observed = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            executor.execute(() -> {
                observed.set(Thread.currentThread());
                done.countDown();
            });
            assertThat(done.await(2L, TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get().getName()).startsWith(config.getThreadNamePrefix() + "-");
            assertThat(observed.get().isDaemon()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aSaturatedPoolRejectsInsteadOfBlockingTheReader() throws Exception {
        LabCacheProperties.LogicalExpire config = TestLabCacheProperties.defaults()
                .getBreakdown().getLogicalExpire();
        config.setRefreshPoolSize(1);
        config.setRefreshQueueCapacity(1);
        ThreadPoolExecutor executor = BreakdownProtectionConfig.newRefreshExecutor(config);
        try {
            CountDownLatch blocker = new CountDownLatch(1);
            executor.execute(() -> {
                try {
                    blocker.await(5L, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.execute(() -> {
            });                     // fills the single-slot queue
            // Third submission must throw immediately: the guard counts the
            // rejection and keeps serving the stale value.
            assertThatThrownBy(() -> executor.execute(() -> {
            })).isInstanceOf(RejectedExecutionException.class);
            blocker.countDown();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void probeSeesTheCachedValueThroughTheSameCacheTheReadPathWrites() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        Cache.ValueWrapper wrapper = mock(Cache.ValueWrapper.class);
        when(cacheManager.getCache("product")).thenReturn(cache);
        when(cache.get(1L)).thenReturn(wrapper);
        when(wrapper.get()).thenReturn(VALUE);

        assertThat(BreakdownProtectionConfig.springCacheProbe(cacheManager).peek(1L))
                .contains(VALUE);
    }

    @Test
    void probeDegradesToMissOnCacheToggleOffAndOnAnyFailure() {
        CacheManager missing = mock(CacheManager.class);
        when(missing.getCache("product")).thenReturn(null);
        assertThat(BreakdownProtectionConfig.springCacheProbe(missing).peek(1L)).isEmpty();

        CacheManager blank = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(blank.getCache("product")).thenReturn(cache);
        when(cache.get(1L)).thenReturn(null);
        assertThat(BreakdownProtectionConfig.springCacheProbe(blank).peek(1L)).isEmpty();

        CacheManager throwing = mock(CacheManager.class);
        Cache broken = mock(Cache.class);
        when(throwing.getCache("product")).thenReturn(broken);
        when(broken.get(any())).thenThrow(new IllegalStateException("redis is down"));
        // The double-check can never turn a working read into a failed one.
        assertThat(BreakdownProtectionConfig.springCacheProbe(throwing).peek(1L)).isEmpty();
    }

    @Test
    void staleStoreBeanUsesTheExternalizedBounds() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();
        LogicalStaleStore store = new BreakdownProtectionConfig()
                .logicalStaleStore(properties);

        assertThat(store.retention()).isEqualTo(Duration.ofSeconds(120));
        assertThat(store.logicalTtl()).isEqualTo(Duration.ofSeconds(30));
    }
}
