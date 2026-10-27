package com.javalearn.lab.seckill.lab.cache.preheat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.catalog.cache.TwoLevelCacheManager;
import com.javalearn.lab.seckill.catalog.cache.TwoLevelCacheSettings;
import com.javalearn.lab.seckill.lab.api.CachePreheatStateResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Preheat semantics without Docker: Redis is a Mockito double, the L1 is the
 * real Caffeine cache created by {@link TwoLevelCacheManager}, and the clock is
 * a mutable test clock. Covers the startup top-N load, incremental skipping,
 * L2 misses/corrupt values, Redis outages, the cache-toggle gate and the
 * {@code trigger=} metric series.
 */
class CachePreheatServiceTest {

    private static final String HOT_KEY = "hot:products";

    private SimpleMeterRegistry registry;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private ZSetOperations<String, String> zset;
    private ObjectMapper objectMapper;
    private HotKeyTracker tracker;
    private TwoLevelCacheManager manager;
    private LabCachePreheatProperties properties;
    private PreheatTestSupport.MutableClock clock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new SimpleMeterRegistry();
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        zset = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(redisTemplate.opsForZSet()).thenReturn(zset);
        objectMapper = new ObjectMapper();
        properties = TestLabCachePreheatProperties.defaults();
        clock = PreheatTestSupport.clock(0L);
        tracker = new HotKeyTracker(properties, redisTemplate, registry, clock);
    }

    @Test
    void startupRunLoadsTheTopNIntoAndNeverWritesL2() throws Exception {
        when(zset.reverseRange(HOT_KEY, 0L, 4L)).thenReturn(ids("1", "2", "3"));
        when(zset.zCard(HOT_KEY)).thenReturn(3L);
        stubProduct(1L);
        stubProduct(2L);
        stubProduct(3L);
        CachePreheatService service = service(true);

        PreheatRun run = service.run(PreheatTrigger.STARTUP);

        assertThat(run.outcome()).isEqualTo(PreheatRun.Outcome.SUCCESS);
        assertThat(run.requested()).isEqualTo(3);
        assertThat(run.loaded()).isEqualTo(3);
        assertThat(run.skippedFresh()).isZero();
        assertThat(run.missing()).isZero();
        assertThat(run.failed()).isZero();

        Cache<Object, Object> l1 = l1();
        assertThat(l1.getIfPresent(1L)).isInstanceOf(ProductResponse.class);
        assertThat(l1.getIfPresent(2L)).isInstanceOf(ProductResponse.class);
        assertThat(((ProductResponse) l1.getIfPresent(3L)).id()).isEqualTo(3L);
        // L1-only warming: rewriting cache:product:<id> would reset the avalanche TTLs.
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));

        assertThat(counter("cache_preheat_runs", "trigger", "startup")).isEqualTo(1.0);
        assertThat(counter("cache_preheat_loaded", "trigger", "startup")).isEqualTo(3.0);
        assertThat(timer("cache_preheat_duration", "trigger", "startup").count()).isEqualTo(1L);
    }

    @Test
    void aSecondRunSkipsEntriesThatAreStillFreshInL1() throws Exception {
        when(zset.reverseRange(HOT_KEY, 0L, 4L)).thenReturn(ids("1", "2", "3"));
        stubProduct(1L);
        stubProduct(2L);
        stubProduct(3L);
        CachePreheatService service = service(true);

        service.run(PreheatTrigger.STARTUP);
        PreheatRun second = service.run(PreheatTrigger.PERIODIC);

        assertThat(second.outcome()).isEqualTo(PreheatRun.Outcome.SUCCESS);
        assertThat(second.requested()).isEqualTo(3);
        assertThat(second.loaded()).isZero();
        assertThat(second.skippedFresh()).isEqualTo(3);
        verify(values, times(1)).get("cache:product:1");
        verify(values, times(1)).get("cache:product:2");
        verify(values, times(1)).get("cache:product:3");
    }

    @Test
    void l2MissesAndCorruptValuesAreReportedWithoutFailingTheRun() throws Exception {
        when(zset.reverseRange(HOT_KEY, 0L, 4L)).thenReturn(ids("1", "2", "3"));
        when(values.get("cache:product:1")).thenReturn(productJson(1L));
        when(values.get("cache:product:2")).thenReturn("{not-json");
        // product 3 has no L2 entry at all
        CachePreheatService service = service(true);

        PreheatRun run = service.run(PreheatTrigger.STARTUP);

        assertThat(run.outcome()).isEqualTo(PreheatRun.Outcome.SUCCESS);
        assertThat(run.loaded()).isEqualTo(1);
        assertThat(run.failed()).isEqualTo(1);
        assertThat(run.missing()).isEqualTo(1);
        assertThat(counter("cache_preheat_failures", "operation", "load")).isEqualTo(1.0);
        assertThat(l1().getIfPresent(3L)).isNull();
    }

    @Test
    void aHotListReadFailureFailsTheRunWithoutThrowing() {
        when(zset.reverseRange(HOT_KEY, 0L, 4L))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        CachePreheatService service = service(true);

        PreheatRun run = service.run(PreheatTrigger.STARTUP);

        assertThat(run.outcome()).isEqualTo(PreheatRun.Outcome.FAILED);
        assertThat(run.requested()).isZero();
        assertThat(run.error()).contains("RedisConnectionFailureException");
        assertThat(counter("cache_preheat_failures", "operation", "hot-list")).isEqualTo(1.0);
        assertThat(service.state().lastRun().outcome()).isEqualTo("failed");
    }

    @Test
    void anL2ReadFailureFailsTheRunAndStopsTheBatch() {
        when(zset.reverseRange(HOT_KEY, 0L, 4L)).thenReturn(ids("1", "2"));
        when(values.get("cache:product:1"))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        CachePreheatService service = service(true);

        PreheatRun run = service.run(PreheatTrigger.STARTUP);

        assertThat(run.outcome()).isEqualTo(PreheatRun.Outcome.FAILED);
        assertThat(run.loaded()).isZero();
        assertThat(counter("cache_preheat_failures", "operation", "load")).isEqualTo(1.0);
        verify(values, never()).get("cache:product:2");
    }

    @Test
    void theCacheToggleOffSkipsWarmingButStillCountsTheRun() {
        when(zset.reverseRange(HOT_KEY, 0L, 4L)).thenReturn(ids("1"));
        CachePreheatService service = service(false);

        PreheatRun run = service.run(PreheatTrigger.STARTUP);

        assertThat(run.outcome()).isEqualTo(PreheatRun.Outcome.CACHE_DISABLED);
        assertThat(run.loaded()).isZero();
        assertThat(counter("cache_preheat_runs", "trigger", "startup")).isEqualTo(1.0);
        assertThat(counter("cache_preheat_loaded", "trigger", "startup")).isZero();
        assertThat(service.state().cacheEnabled()).isFalse();
        verify(values, never()).get(anyString());
    }

    @Test
    void manualRunsUseTheirOwnMetricSeriesAndStateShowsTheLastRun() throws Exception {
        when(zset.reverseRange(HOT_KEY, 0L, 4L)).thenReturn(ids("1"));
        when(zset.zCard(HOT_KEY)).thenReturn(1L);
        stubProduct(1L);
        CachePreheatService service = service(true);

        service.run(PreheatTrigger.MANUAL);

        assertThat(counter("cache_preheat_runs", "trigger", "manual")).isEqualTo(1.0);
        assertThat(counter("cache_preheat_loaded", "trigger", "manual")).isEqualTo(1.0);
        assertThat(counter("cache_preheat_runs", "trigger", "startup")).isZero();

        CachePreheatStateResponse state = service.state();
        assertThat(state.enabled()).isTrue();
        assertThat(state.cacheEnabled()).isTrue();
        assertThat(state.hotListKey()).isEqualTo(HOT_KEY);
        assertThat(state.topN()).isEqualTo(5L);
        assertThat(state.hotListSize()).isEqualTo(1L);
        assertThat(state.running()).isFalse();
        assertThat(state.lastRun().trigger()).isEqualTo("manual");
        assertThat(state.lastRun().outcome()).isEqualTo("success");
        assertThat(state.lastRun().loaded()).isEqualTo(1);
        assertThat(state.lastRun().at()).isNotBlank();
        assertThat(registry.find("cache_preheat_hot_list_size").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void aConcurrentRunReturnsBusyWithoutTouchingMetrics() throws Exception {
        when(zset.reverseRange(HOT_KEY, 0L, 4L)).thenReturn(ids("1"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(values.get("cache:product:1")).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return productJson(1L);
        });
        CachePreheatService service = service(true);
        AtomicReference<PreheatRun> first = new AtomicReference<>();
        Thread worker = new Thread(() -> first.set(service.run(PreheatTrigger.STARTUP)));
        worker.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            PreheatRun concurrent = service.run(PreheatTrigger.MANUAL);

            assertThat(concurrent.outcome()).isEqualTo(PreheatRun.Outcome.BUSY);
            assertThat(counter("cache_preheat_runs", "trigger", "manual")).isZero();
        } finally {
            release.countDown();
            worker.join(5_000L);
        }

        assertThat(worker.isAlive()).isFalse();
        assertThat(first.get().outcome()).isEqualTo(PreheatRun.Outcome.SUCCESS);
        assertThat(counter("cache_preheat_runs", "trigger", "startup")).isEqualTo(1.0);
    }

    private CachePreheatService service(boolean cacheEnabled) {
        manager = new TwoLevelCacheManager(redisTemplate, objectMapper, registry,
                PreheatTestSupport.toggles(cacheEnabled));
        return new CachePreheatService(properties, redisTemplate, objectMapper,
                manager, registry, tracker, clock, TwoLevelCacheSettings.defaults());
    }

    private void stubProduct(long id) throws Exception {
        when(values.get("cache:product:" + id)).thenReturn(productJson(id));
    }

    private String productJson(long id) throws Exception {
        return objectMapper.writeValueAsString(new ProductResponse(
                id, "product-" + id, new BigDecimal("9.90"), "ON_SALE", "test"));
    }

    private static Set<String> ids(String... ids) {
        return new LinkedHashSet<>(java.util.List.of(ids));
    }

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> l1() {
        return (Cache<Object, Object>) ((com.javalearn.lab.seckill.catalog.cache.TwoLevelCache)
                manager.getCache(TwoLevelCacheManager.PRODUCT_CACHE)).getNativeCache();
    }

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private Timer timer(String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        assertThat(timer).as("timer %s %s", name, java.util.Arrays.toString(tags)).isNotNull();
        return timer;
    }
}
