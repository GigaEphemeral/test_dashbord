package com.javalearn.lab.seckill.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.lab.config.LabToggleProperties;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B0-03: the Caffeine-native L1 stats (recordStats snapshot plus
 * estimatedSize) must reach Micrometer as real series, with only
 * low-cardinality tags, an "estimated" semantic on the size metric, and NO
 * fake zero when L1 is disabled (no cache instance -> no series).
 *
 * <p>Redis is mocked (no container): the tests exercise the L1 and the metric
 * bindings, never the L2 content.</p>
 */
class TwoLevelCacheMicrometerStatsTest {

    private static final ProductResponse PRODUCT = new ProductResponse(
            1L, "键盘", new BigDecimal("1.00"), "ON_SALE", "a");
    private static final ProductResponse PRODUCT_2 = new ProductResponse(
            2L, "耳机", new BigDecimal("2.00"), "ON_SALE", "b");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void caffeineHitsAndMissesCountersTrackRealL1Reads() {
        Chip chip = new Chip();
        TwoLevelCache cache = chip.cache();

        cache.get(1L);                    // L1 miss (empty)
        cache.put(1L, PRODUCT);
        cache.get(1L);                    // L1 hit

        assertThat(functionCounter(chip.registry, "cache_l1_caffeine_requests", "result", "miss"))
                .isGreaterThanOrEqualTo(1.0);
        // One Spring-cache hit is two Caffeine reads (getIfPresent + refresh
        // trigger), so assert the monotonic truth, not an implementation ratio.
        assertThat(functionCounter(chip.registry, "cache_l1_caffeine_requests", "result", "hit"))
                .isGreaterThanOrEqualTo(2.0);
    }

    @Test
    void caffeineHitRatioGaugeMatchesTheCaffeineStatsSnapshot() {
        Chip chip = new Chip();
        TwoLevelCache cache = chip.cache();

        cache.get(1L);
        cache.put(1L, PRODUCT);
        cache.get(1L);

        double expected = nativeCache(cache).stats().hitRate();
        assertThat(gauge(chip.registry, "cache_l1_caffeine_hit_ratio").value())
                .isEqualTo(expected)
                .isBetween(0.0, 1.0);
    }

    @Test
    void estimatedSizeGaugeTracksTheLiveEntryCount() {
        Chip chip = new Chip();
        TwoLevelCache cache = chip.cache();

        assertThat(gauge(chip.registry, "cache_l1_estimated_size").value()).isZero();

        cache.put(1L, PRODUCT);
        cache.put(2L, PRODUCT_2);
        nativeCache(cache).cleanUp();
        assertThat(gauge(chip.registry, "cache_l1_estimated_size").value()).isEqualTo(2.0);

        cache.evict(1L);
        nativeCache(cache).cleanUp();
        assertThat(gauge(chip.registry, "cache_l1_estimated_size").value()).isEqualTo(1.0);
    }

    @Test
    void evictionsCounterRisesWhenMaximumSizeEvictsAnEntry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TwoLevelCache cache = newCache(registry, new TwoLevelCacheSettings(
                1L, Duration.ofSeconds(10), Duration.ofSeconds(60)));

        assertThat(functionCounter(registry, "cache_l1_caffeine_evictions")).isZero();

        cache.put(1L, PRODUCT);
        cache.put(2L, PRODUCT_2);
        cache.put(3L, PRODUCT);
        nativeCache(cache).cleanUp();

        assertThat(functionCounter(registry, "cache_l1_caffeine_evictions"))
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void evictionsCounterRisesWhenEntriesExpire() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TwoLevelCache cache = newCache(registry, new TwoLevelCacheSettings(
                10L, Duration.ofMillis(20), Duration.ofMillis(80)));

        cache.put(1L, PRODUCT);
        Thread.sleep(200L);
        nativeCache(cache).cleanUp();

        assertThat(functionCounter(registry, "cache_l1_caffeine_evictions"))
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void caffeineAndApplicationSeriesCoexistAsTwoSourceViews() {
        Chip chip = new Chip();
        TwoLevelCache cache = chip.cache();

        cache.put(1L, PRODUCT);
        cache.get(1L);

        // Application path counter: exactly one hit for one Spring-cache get.
        assertThat(chip.registry.get("cache_l1_requests")
                .tags("cache", "product", "result", "hit").counter().count())
                .isEqualTo(1.0);
        // Caffeine stats: the same read observed twice by L1. Both series
        // exist side by side under different names - no collision, no alias.
        assertThat(functionCounter(chip.registry, "cache_l1_caffeine_requests", "result", "hit"))
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void onlyLowCardinalityTagsAreRegistered() {
        Chip chip = new Chip();
        chip.cache().get(1L);

        for (Meter meter : chip.registry.getMeters()) {
            String name = meter.getId().getName();
            if (!name.startsWith("cache_l1_caffeine") && !name.equals("cache_l1_estimated_size")) {
                continue;
            }
            assertThat(meter.getId().getTag("cache")).isEqualTo("product");
            // instance is the scrape target's job, never a Java-side tag.
            assertThat(meter.getId().getTag("instance")).isNull();
            assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getKey()).isIn("cache", "result"));
        }
    }

    @Test
    void statsRegistrationSwitchOffPublishesNoCaffeineSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TwoLevelCache cache = newCache(registry, new TwoLevelCacheSettings(
                10L, Duration.ofSeconds(10), Duration.ofSeconds(60), false));

        cache.put(1L, PRODUCT);
        cache.get(1L);

        assertThat(registry.find("cache_l1_caffeine_requests").meter()).isNull();
        assertThat(registry.find("cache_l1_caffeine_hit_ratio").meter()).isNull();
        assertThat(registry.find("cache_l1_caffeine_evictions").meter()).isNull();
        assertThat(registry.find("cache_l1_estimated_size").meter()).isNull();
        // The pre-existing application-path meters stay untouched.
        assertThat(registry.find("cache_l1_requests").meter()).isNotNull();
        assertThat(registry.find("cache_l1_hit_ratio").meter()).isNotNull();
    }

    @Test
    void cacheToggleOffMeansNoSeriesAtAllAndNeverAFakeZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StringRedisTemplate redis = mockRedis();
        TwoLevelCacheManager manager = new TwoLevelCacheManager(
                redis, objectMapper, registry, toggles(false));

        org.springframework.cache.Cache cache =
                manager.getCache(TwoLevelCacheManager.PRODUCT_CACHE);

        assertThat(cache).isInstanceOf(NoOpCache.class);
        // "L1 disabled" is exported as ABSENCE of the series, not 0.
        assertThat(registry.find("cache_l1_estimated_size").gauge()).isNull();
        assertThat(registry.find("cache_l1_caffeine_requests").meter()).isNull();
        assertThat(registry.find("cache_l1_caffeine_hit_ratio").meter()).isNull();
        assertThat(registry.find("cache_l1_caffeine_evictions").meter()).isNull();
        assertThat(registry.getMeters()).noneMatch(m ->
                m.getId().getName().startsWith("cache_l1_caffeine")
                        || m.getId().getName().equals("cache_l1_estimated_size"));
    }

    @Test
    void cacheToggleOnRegistersTheFullCaffeineStatsSet() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TwoLevelCacheManager manager = new TwoLevelCacheManager(
                mockRedis(), objectMapper, registry, toggles(true));

        manager.getCache(TwoLevelCacheManager.PRODUCT_CACHE);

        assertThat(registry.find("cache_l1_caffeine_requests")
                .tags("cache", "product", "result", "hit").meter()).isNotNull();
        assertThat(registry.find("cache_l1_caffeine_requests")
                .tags("cache", "product", "result", "miss").meter()).isNotNull();
        assertThat(registry.find("cache_l1_caffeine_hit_ratio").meter()).isNotNull();
        assertThat(registry.find("cache_l1_caffeine_evictions").meter()).isNotNull();
        assertThat(registry.find("cache_l1_estimated_size").meter()).isNotNull();
    }

    @Test
    void threeArgSettingsKeepStatsRegistrationEnabled() {
        // Historical constructor shape used by tests/tools must not silently
        // turn the B0-03 series off.
        assertThat(new TwoLevelCacheSettings(
                10L, Duration.ofSeconds(10), Duration.ofSeconds(60))
                        .micrometerStatsEnabled()).isTrue();
    }

    @Test
    void prometheusExportsUseCacheNamingAndEstimatedHelpText() {
        PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        TwoLevelCache cache = newCache(registry, TwoLevelCacheSettings.defaults());
        cache.get(1L);
        cache.put(1L, PRODUCT);
        cache.get(1L);

        String scrape = registry.scrape();

        assertThat(scrape).contains("cache_l1_caffeine_requests_total");
        assertThat(scrape).contains("cache_l1_caffeine_evictions_total");
        assertThat(scrape).contains("cache_l1_caffeine_hit_ratio");
        assertThat(scrape).contains("cache_l1_estimated_size");
        // "estimated" semantics must survive into the exported help text.
        assertThat(scrape).containsPattern(
                "(?s)# HELP cache_l1_estimated_size .*[Ee]stimated.*\\ncache_l1_estimated_size");
        // Micrometer reserved-suffix traps: neither name was stripped or renamed.
        assertThat(scrape).doesNotContain("cache_l1_estimated_size_info");
        assertThat(scrape).doesNotContain("cache_l1_estimated_size_created");
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

    private static StringRedisTemplate mockRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        return redis;
    }

    private TwoLevelCache newCache(io.micrometer.core.instrument.MeterRegistry registry,
                                   TwoLevelCacheSettings settings) {
        return new TwoLevelCache("product", ProductResponse.class, mockRedis(),
                objectMapper, registry, settings);
    }

    private static double functionCounter(SimpleMeterRegistry registry, String name,
                                          String... tags) {
        return registry.get(name).tags(tags).functionCounter().count();
    }

    private static Gauge gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).tag("cache", "product").gauge();
    }

    @SuppressWarnings("unchecked")
    private static Cache<Object, Object> nativeCache(TwoLevelCache cache) {
        return (Cache<Object, Object>) cache.getNativeCache();
    }

    /** Redis-mocked fixture with the production-shaped default settings. */
    private final class Chip {

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

        private TwoLevelCache cache() {
            return new TwoLevelCache("product", ProductResponse.class, mockRedis(),
                    objectMapper, registry);
        }
    }
}
